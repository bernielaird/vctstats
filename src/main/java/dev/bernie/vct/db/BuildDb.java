package dev.bernie.vct.db;

import dev.bernie.vct.parse.EventFilter;
import dev.bernie.vct.parse.MatchPage;
import dev.bernie.vct.parse.MatchPage.MapResult;
import dev.bernie.vct.parse.MatchPageParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pass 4: turn every saved match page into rows in vct.db, then check the result.
 *
 * Writes to a temporary file and swaps it in at the end, so a crash or a parser
 * exception never leaves a half-built database where the old one was.
 */
public class BuildDb {

    private static final int EXAMPLES_PER_CHECK = 5;

    private final MatchPageParser parser = new MatchPageParser();
    private final Map<String, List<String>> issues = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public void run(Path matchDir, Path dbFile) throws Exception {
        long started = System.currentTimeMillis();
        List<MatchPage> pages = parseAll(matchDir);

        // Chronological, so each team ends up with its most recent name.
        pages.sort(Comparator.comparing(MatchPage::startTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(MatchPage::matchId));

        Path tmp = dbFile.resolveSibling(dbFile.getFileName() + ".building");
        Files.deleteIfExists(tmp);
        try (VctDatabase db = new VctDatabase(tmp.toString())) {
            for (MatchPage p : pages) {
                db.putTeam(p.team1());
                db.putTeam(p.team2());
            }
            for (MatchPage p : pages) {
                db.insertMatch(p);
                validate(p);
            }
            db.commit();
        }
        Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING);

        printReport(pages, System.currentTimeMillis() - started, dbFile);
    }

    private List<MatchPage> parseAll(Path matchDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(matchDir)) {
            files = s.filter(f -> f.getFileName().toString().matches("\\d+\\.html")).sorted().toList();
        }
        List<MatchPage> pages = new ArrayList<>();
        for (Path f : files) {
            long matchId = Long.parseLong(f.getFileName().toString().replace(".html", ""));
            MatchPage p;
            try {
                p = parser.parse(matchId, Files.readString(f));
            } catch (RuntimeException e) {
                issue("page could not be parsed", matchId + ": " + e.getMessage());
                continue;
            }
            if (!EventFilter.isWanted(p.eventName())) {
                count("skipped: not a VCT event");
                continue;
            }
            pages.add(p);
            if (pages.size() % 250 == 0) {
                System.out.printf("Parsed %d matches...%n", pages.size());
            }
        }
        return pages;
    }

    /** Things that should always hold for a real VCT match. Each failure is listed, not fixed. */
    private void validate(MatchPage p) {
        String id = String.valueOf(p.matchId());
        if (p.isShowmatch()) {
            count("showmatches (kept, flagged is_showmatch)");
        }
        if (p.startTime() == null) {
            issue("no start time", id);
        }
        if (p.patch() == null) {
            count("no patch listed");
        }
        if (p.maps().isEmpty()) {
            issue("no maps (forfeit?)", id + " " + p.team1Score() + "-" + p.team2Score());
            return;
        }

        int won1 = 0;
        int won2 = 0;
        for (MapResult m : p.maps()) {
            String mid = id + "/" + m.gameId();
            if (m.team1Score() > m.team2Score()) {
                won1++;
            } else if (m.team2Score() > m.team1Score()) {
                won2++;
            } else {
                issue("map ended in a tie", mid + " " + m.team1Score() + "-" + m.team2Score());
            }
            if (m.mapName() == null || m.mapName().isBlank()) {
                issue("map has no name", mid);
            }
            if (!m.hasSideSplit()) {
                count("maps without atk/def split (stored as NULL)");
            }
            if (!isValidMapScore(m.team1Score(), m.team2Score())) {
                issue("map score impossible in Valorant", mid + " " + m.team1Score() + "-" + m.team2Score());
            }
        }
        if (won1 != p.team1Score() || won2 != p.team2Score()) {
            issue("maps won don't match series score",
                    id + " series " + p.team1Score() + "-" + p.team2Score() + ", maps " + won1 + "-" + won2);
        }
        if (p.bestOf() > 0 && Math.max(p.team1Score(), p.team2Score()) != p.bestOf() / 2 + 1) {
            issue("series winner didn't reach a majority of best-of",
                    id + " Bo" + p.bestOf() + " " + p.team1Score() + "-" + p.team2Score());
        }
    }

    /** First to 13; from 12-12 overtime runs until someone leads by 2. */
    static boolean isValidMapScore(int a, int b) {
        int hi = Math.max(a, b);
        int lo = Math.min(a, b);
        return hi == 13 && lo <= 11 || hi >= 14 && hi - lo == 2;
    }

    private void issue(String check, String example) {
        List<String> list = issues.computeIfAbsent(check, k -> new ArrayList<>());
        list.add(example);
    }

    private void count(String what) {
        counts.merge(what, 1, Integer::sum);
    }

    private void printReport(List<MatchPage> pages, long millis, Path dbFile) {
        long maps = pages.stream().mapToLong(p -> p.maps().size()).sum();
        long teams = pages.stream().flatMap(p -> Stream.of(p.team1().id(), p.team2().id())).distinct().count();
        long events = pages.stream().map(MatchPage::eventId).distinct().count();
        Instant first = pages.stream().map(MatchPage::startTime).filter(t -> t != null).min(Instant::compareTo).orElse(null);
        Instant last = pages.stream().map(MatchPage::startTime).filter(t -> t != null).max(Instant::compareTo).orElse(null);

        System.out.printf("%nBuilt %s in %.1fs%n", dbFile, millis / 1000.0);
        System.out.printf("  %d events, %d teams, %d matches, %d maps (%s to %s)%n",
                events, teams, pages.size(), maps, first, last);
        counts.forEach((k, v) -> System.out.printf("  %-45s %d%n", k, v));

        if (issues.isEmpty()) {
            System.out.println("\nValidation: no problems found.");
            return;
        }
        System.out.println("\nValidation problems (rows are still written; check these by hand):");
        issues.forEach((check, examples) -> {
            System.out.printf("  %-45s %d%n", check, examples.size());
            examples.stream().limit(EXAMPLES_PER_CHECK).forEach(e -> System.out.println("      " + e));
        });
    }
}
