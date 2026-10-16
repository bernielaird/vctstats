package dev.bernie.vct.cli;

import dev.bernie.vct.db.BuildDb;
import dev.bernie.vct.fetch.Fetcher;
import dev.bernie.vct.parse.EventFilter;
import dev.bernie.vct.parse.MatchPageParser;
import dev.bernie.vct.parse.ResultsPageParser;
import dev.bernie.vct.parse.ResultsPageParser.MatchLink;
import dev.bernie.vct.store.MatchQueue;
import dev.bernie.vct.store.MatchQueue.QueuedMatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Commands:
 *   discover [--max-pages N] [--since YYYY-MM-DD]
 *                              Pass 1: walk results pages, queue VCT/Masters/Champions match IDs
 *   fetch    [--limit N]       Pass 2: download queued match pages into data/raw/matches
 *   fetch-logs [--limit N]     Pass 3: download each map's 2D replay page into data/raw/logs
 *   update                     After the first scrape: queue new matches, then fetch + fetch-logs
 *   build-db                   Pass 4: parse saved match pages into data/vct.db (no network)
 *   status                     Show how many matches are pending / done / failed
 *   retry-failed               Move failed matches back to pending
 */
public class Main {

    // vlr.gg should be able to reach whoever runs the scraper. Set it in .env (see .env.example).
    private static final String CONTACT_KEY = "VCT_CONTACT_EMAIL";
    private static final Set<String> NETWORK_COMMANDS = Set.of("discover", "fetch", "fetch-logs", "update");

    private static final Path DATA_DIR = Path.of("data");
    private static final Path RESULTS_CACHE = DATA_DIR.resolve("raw/results");
    private static final Path MATCH_CACHE = DATA_DIR.resolve("raw/matches");
    private static final Path LOGS_CACHE = DATA_DIR.resolve("raw/logs");
    private static final String DB_PATH = DATA_DIR.resolve("scrape.db").toString();
    private static final Path VCT_DB = DATA_DIR.resolve("vct.db");

    private static final int MAX_ATTEMPTS = 3;

    // vlr.gg can take a while to post stats after a match. Younger matches are left for a later run.
    static final Duration MIN_MATCH_AGE = Duration.ofHours(12);

    // An empty replay page for a match younger than this is retried later instead of saved.
    // Older empty pages are saved as-is: those matches (pre-2024, mostly) never get a replay.
    static final Duration REPLAY_GRACE = Duration.ofDays(7);

    // Safety cap for update, in case it never runs into a match it already knows.
    static final int MAX_UPDATE_PAGES = 30;

    // Franchised VCT started in 2023. Earlier matches are skipped unless --since says otherwise.
    static final LocalDate DEFAULT_SINCE = LocalDate.of(2023, 1, 1);

    /** Where results pages come from: the cache for discover, always fresh for update. */
    interface PageSource {
        String page(int n) throws Exception;
    }

    record PageCounts(int kept, int known, int tooRecent, int tooOld, int added) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Files.createDirectories(DATA_DIR);

        if (args[0].equals("build-db")) {
            new BuildDb().run(MATCH_CACHE, VCT_DB);
            return;
        }

        // Only commands that hit vlr.gg need the contact email; status and retry-failed don't.
        Fetcher fetcher = null;
        if (NETWORK_COMMANDS.contains(args[0])) {
            String contact = new LocalConfig(Path.of(".env")).get(CONTACT_KEY);
            if (contact == null) {
                System.err.printf("Set %s first: copy .env.example to .env and fill in your email.%n", CONTACT_KEY);
                System.exit(1);
            }
            fetcher = new Fetcher("vct-ratings-student-project (contact: " + contact + ")", 1500, 1500);
        }
        try (MatchQueue queue = new MatchQueue(DB_PATH)) {
            switch (args[0]) {
                case "discover" -> discover(fetcher, queue,
                        intFlag(args, "--max-pages", Integer.MAX_VALUE), dateFlag(args, "--since", DEFAULT_SINCE));
                case "fetch" -> fetch(fetcher, queue, intFlag(args, "--limit", Integer.MAX_VALUE));
                case "fetch-logs" -> fetchLogs(fetcher, queue, intFlag(args, "--limit", Integer.MAX_VALUE));
                case "update" -> update(fetcher, queue);
                case "status" -> System.out.println(queue.statusCounts());
                case "retry-failed" -> System.out.println("Reset " + queue.resetFailed() + " matches");
                default -> usage();
            }
        }
    }

    /** Pass 1: page through the results list until it runs out or gets older than {@code since}. */
    static void discover(Fetcher fetcher, MatchQueue queue, int maxPages, LocalDate since) throws Exception {
        ResultsPageParser parser = new ResultsPageParser();
        int totalNew = 0;

        for (int page = 1; page <= maxPages; page++) {
            String url = ResultsPageParser.resultsPageUrl(page);
            // Cached so a crash mid-discovery doesn't refetch pages. Results pages shift
            // as new matches are played, so use `update` (which skips the cache) afterwards.
            String html = fetcher.fetch(url, RESULTS_CACHE.resolve("page-" + page + ".html"));
            List<MatchLink> links = parser.parse(html);

            if (links.isEmpty()) {
                System.out.printf("Page %d had no matches: reached the end.%n", page);
                break;
            }
            PageCounts c = queuePage(links, queue, since);
            totalNew += c.added();
            printPage(page, links.size(), c);

            if (isEntirelyBefore(links, since)) {
                System.out.printf("Page %d is all before %s: stopping.%n", page, since);
                break;
            }
        }
        System.out.printf("Discovery done. %d new matches queued. %s%n", totalNew, queue.statusCounts());
    }

    /**
     * Run after the first full scrape: queue matches played since, then download them.
     * fetch and fetch-logs already skip anything on disk, so only new pages are requested.
     */
    static void update(Fetcher fetcher, MatchQueue queue) throws Exception {
        if (queue.isEmpty()) {
            System.out.println("Nothing scraped yet. Run discover first; update only adds new matches.");
            return;
        }
        int added = discoverNew(n -> fetcher.fetchFresh(ResultsPageParser.resultsPageUrl(n)),
                queue, MAX_UPDATE_PAGES, DEFAULT_SINCE);
        System.out.printf("Update: %d new matches queued.%n", added);
        fetch(fetcher, queue, Integer.MAX_VALUE);
        fetchLogs(fetcher, queue, Integer.MAX_VALUE);
    }

    /**
     * Reads results pages newest-first until it reaches matches already in the queue.
     * Everything past that point is older and was covered by an earlier run, but one extra
     * page is read in case a match was posted late and sits just past the boundary.
     */
    static int discoverNew(PageSource source, MatchQueue queue, int maxPages, LocalDate since) throws Exception {
        ResultsPageParser parser = new ResultsPageParser();
        int added = 0;
        boolean reachedKnown = false;

        for (int page = 1; page <= maxPages; page++) {
            List<MatchLink> links = parser.parse(source.page(page));
            if (links.isEmpty()) {
                break;
            }
            PageCounts c = queuePage(links, queue, since);
            added += c.added();
            printPage(page, links.size(), c);

            if (reachedKnown || isEntirelyBefore(links, since)) {
                return added; // that was the extra page
            }
            reachedKnown = c.known() > 0;
        }
        if (!reachedKnown) {
            System.out.printf("Warning: read %d pages without reaching known matches. "
                    + "If you've been away a long time, run discover instead.%n", maxPages);
        }
        return added;
    }

    /** Queues a page's wanted matches. Shared by discover and update. */
    static PageCounts queuePage(List<MatchLink> links, MatchQueue queue, LocalDate since) throws Exception {
        int kept = 0;
        int known = 0;
        int tooRecent = 0;
        int tooOld = 0;
        int added = 0;
        for (MatchLink link : links) {
            if (!EventFilter.isWanted(link.eventName())) {
                continue;
            }
            kept++;
            if (queue.contains(link.matchId())) {
                known++;
            } else if (link.date() != null && link.date().isBefore(since)) {
                tooOld++;
            } else if (link.age() != null && link.age().compareTo(MIN_MATCH_AGE) < 0) {
                tooRecent++;
            } else if (queue.enqueue(link.matchId(), link.url())) {
                added++;
            }
        }
        return new PageCounts(kept, known, tooRecent, tooOld, added);
    }

    /** Results run newest-first, so a page that's all before the cutoff means everything after is too. */
    static boolean isEntirelyBefore(List<MatchLink> links, LocalDate since) {
        return links.stream().allMatch(l -> l.date() != null && l.date().isBefore(since));
    }

    private static void printPage(int page, int total, PageCounts c) {
        String extra = (c.tooRecent() > 0 ? String.format(", %d too recent", c.tooRecent()) : "")
                + (c.tooOld() > 0 ? String.format(", %d before cutoff", c.tooOld()) : "");
        System.out.printf("Page %d: %d matches, %d kept (%d new%s)%n", page, total, c.kept(), c.added(), extra);
    }

    /** Pass 2: download every pending match page. Safe to stop and restart. */
    static void fetch(Fetcher fetcher, MatchQueue queue, int limit) throws Exception {
        int fetched = 0;
        while (fetched < limit) {
            List<QueuedMatch> batch = queue.nextPending(Math.min(50, limit - fetched), MAX_ATTEMPTS);
            if (batch.isEmpty()) {
                break;
            }
            for (QueuedMatch m : batch) {
                try {
                    fetcher.fetch(m.url(), MATCH_CACHE.resolve(m.matchId() + ".html"));
                    queue.markDone(m.matchId());
                    fetched++;
                    if (fetched % 25 == 0) {
                        System.out.printf("Fetched %d matches. %s%n", fetched, queue.statusCounts());
                    }
                } catch (Exception e) {
                    System.err.printf("Match %d failed: %s%n", m.matchId(), e.getMessage());
                    queue.markAttemptFailed(m.matchId(), e.toString(), MAX_ATTEMPTS);
                }
            }
        }
        System.out.printf("Fetch done. %d downloaded this run. %s%n", fetched, queue.statusCounts());
    }

    /**
     * Pass 3: download each map's 2D replay page for every fetched match.
     * Replay pages already on disk are skipped, so re-running only fills gaps.
     */
    static void fetchLogs(Fetcher fetcher, MatchQueue queue, int limit) throws Exception {
        MatchPageParser parser = new MatchPageParser();
        int fetched = 0;
        int failed = 0;
        int waiting = 0;

        for (long matchId : queue.doneMatchIds()) {
            if (fetched >= limit) {
                break;
            }
            String matchHtml = Files.readString(MATCH_CACHE.resolve(matchId + ".html"));
            Instant played = parser.matchTime(matchHtml);
            boolean recent = played != null
                    && Duration.between(played, Instant.now()).compareTo(REPLAY_GRACE) < 0;

            for (long gameId : parser.gameIds(matchHtml)) {
                Path logsFile = LOGS_CACHE.resolve(matchId + "-" + gameId + ".html");
                if (Files.exists(logsFile) || fetched >= limit) {
                    continue;
                }
                try {
                    String html = fetcher.fetchFresh(MatchPageParser.logsUrl(matchId, gameId));
                    if (recent && !MatchPageParser.hasReplayData(html)) {
                        waiting++; // not posted yet; no file, so the next run tries again
                        continue;
                    }
                    Files.createDirectories(LOGS_CACHE);
                    Files.writeString(logsFile, html);
                    fetched++;
                    if (fetched % 25 == 0) {
                        System.out.printf("Fetched %d replay pages.%n", fetched);
                    }
                } catch (Exception e) {
                    // Not tracked in the queue: the missing file means the next run retries it.
                    System.err.printf("Replay %d-%d failed: %s%n", matchId, gameId, e.getMessage());
                    failed++;
                }
            }
        }
        System.out.printf("Fetch-logs done. %d replay pages downloaded, %d not posted yet, %d failed.%n",
                fetched, waiting, failed);
    }

    private static int intFlag(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }

    private static LocalDate dateFlag(String[] args, String name, LocalDate defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return LocalDate.parse(args[i + 1]);
            }
        }
        return defaultValue;
    }

    private static void usage() {
        System.out.println("""
            Usage: ./gradlew run --args="<command> [options]"
              discover [--max-pages N] [--since YYYY-MM-DD]
                                         queue match IDs from the results pages (default since 2023-01-01)
              fetch [--limit N]          download queued match pages
              fetch-logs [--limit N]     download each map's 2D replay page
              update                     queue new matches since the last run, then fetch them
              build-db                   rebuild data/vct.db from the saved pages
              status                     show queue progress
              retry-failed               re-queue failed matches""");
    }
}
