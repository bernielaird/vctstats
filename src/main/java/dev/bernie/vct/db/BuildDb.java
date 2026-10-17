package dev.bernie.vct.db;

import dev.bernie.vct.parse.EventFilter;
import dev.bernie.vct.parse.MatchPage;
import dev.bernie.vct.parse.MatchPage.MapResult;
import dev.bernie.vct.parse.MatchPage.PlayerStats;
import dev.bernie.vct.parse.MatchPage.RoundResult;
import dev.bernie.vct.parse.MatchPage.SideStats;
import dev.bernie.vct.parse.MatchPageParser;
import dev.bernie.vct.parse.ReplayPage;
import dev.bernie.vct.parse.ReplayPageParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Pass 4: turn every saved match page and replay page into rows in vct.db, then check the result.
 *
 * Pages are parsed in parallel; all writes happen on one thread in one transaction.
 * Writes to a temporary file and swaps it in at the end, so a crash or a parser
 * exception never leaves a half-built database where the old one was.
 */
public class BuildDb {

    private static final int EXAMPLES_PER_CHECK = 5;
    private static final int REPLAY_CHUNK = 64; // matches whose replays are parsed together

    private final MatchPageParser parser = new MatchPageParser();
    private final ReplayPageParser replayParser = new ReplayPageParser();
    private final Map<String, List<String>> issues = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Long> counts = Collections.synchronizedMap(new LinkedHashMap<>());

    private record ParsedReplay(MatchPage match, MapResult map, ReplayPage replay) {}

    public void run(Path matchDir, Path logsDir, Path dbFile) throws Exception {
        long started = System.currentTimeMillis();
        List<MatchPage> pages = parseAll(matchDir);

        // Chronological, so each team and player ends up with its most recent name.
        pages.sort(Comparator.comparing(MatchPage::startTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(MatchPage::matchId));

        Path tmp = dbFile.resolveSibling(dbFile.getFileName() + ".building");
        Files.deleteIfExists(tmp);
        try (VctDatabase db = new VctDatabase(tmp.toString())) {
            for (MatchPage p : pages) {
                db.putTeam(p.team1());
                db.putTeam(p.team2());
                for (MapResult m : p.maps()) {
                    for (PlayerStats ps : m.players()) {
                        db.putPlayer(ps);
                    }
                }
            }
            for (MatchPage p : pages) {
                db.insertMatch(p);
                validate(p);
            }
            System.out.printf("Matches written (%.0fs). Reading replays...%n",
                    (System.currentTimeMillis() - started) / 1000.0);
            insertReplays(db, pages, logsDir);
            db.commit();
        }
        try {
            Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException e) {
            // Windows locks a database that a viewer (SQLite Viewer, DB Browser) has open.
            System.err.printf("%nCould not replace %s: %s%n"
                    + "Close any program that has it open, then rename %s to %s (or run build-db again).%n",
                    dbFile, e.getReason(), tmp.getFileName(), dbFile.getFileName());
            System.exit(1);
        }

        printReport(pages, System.currentTimeMillis() - started, dbFile);
    }

    private List<MatchPage> parseAll(Path matchDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(matchDir)) {
            files = s.filter(f -> f.getFileName().toString().matches("\\d+\\.html")).sorted().toList();
        }
        List<MatchPage> pages = files.parallelStream().map(f -> {
            long matchId = Long.parseLong(f.getFileName().toString().replace(".html", ""));
            try {
                MatchPage p = parser.parse(matchId, Files.readString(f));
                if (!EventFilter.isWanted(p.eventName())) {
                    count("skipped: not a VCT event");
                    return null;
                }
                return p;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                issue("page could not be parsed", matchId + ": " + e);
                return null;
            }
        }).filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        System.out.printf("Parsed %d match pages.%n", pages.size());
        return pages;
    }

    private void insertReplays(VctDatabase db, List<MatchPage> pages, Path logsDir) throws Exception {
        int done = 0;
        for (int i = 0; i < pages.size(); i += REPLAY_CHUNK) {
            List<MatchPage> chunk = pages.subList(i, Math.min(pages.size(), i + REPLAY_CHUNK));
            List<ParsedReplay> parsed = chunk.parallelStream()
                    .flatMap(p -> p.maps().stream().map(m -> parseReplay(p, m, logsDir)))
                    .filter(Objects::nonNull)
                    .toList();
            for (ParsedReplay r : parsed) {
                Map<String, Long> byName = new HashMap<>();
                for (PlayerStats ps : r.map().players()) {
                    byName.put(ps.name(), ps.playerId());
                }
                List<String> unmatched = db.insertReplay(r.map().gameId(), r.replay(), byName,
                        r.match().team1().id(), r.match().team2().id());
                validateReplay(r, unmatched);
                if (!r.match().isShowmatch()) {
                    db.insertGameStates(r.map().gameId(), r.replay(), r.map().rounds(),
                            r.match().team1().id(), r.match().team2().id());
                }
            }
            done += chunk.size();
            if (done % 320 < REPLAY_CHUNK) {
                System.out.printf("Replays: %d / %d matches...%n", done, pages.size());
            }
        }
    }

    private ParsedReplay parseReplay(MatchPage p, MapResult m, Path logsDir) {
        Path file = logsDir.resolve(p.matchId() + "-" + m.gameId() + ".html");
        if (!Files.exists(file)) {
            count("maps without a replay file");
            return null;
        }
        try {
            ReplayPage replay = replayParser.parse(Files.readString(file));
            if (replay.rounds().isEmpty()) {
                count("maps whose replay page has no data (China etc.)");
                return null;
            }
            return new ParsedReplay(p, m, replay);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            issue("replay could not be parsed", p.matchId() + "/" + m.gameId() + ": " + e);
            return null;
        }
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
        if (p.team1().tag() == null || p.team2().tag() == null) {
            count("matches without team tags");
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
            validatePlayers(p, m, mid);
            validateRounds(m, mid);
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

    private void validatePlayers(MatchPage p, MapResult m, String mid) {
        long team1 = m.players().stream().filter(ps -> ps.team() == 1).count();
        long team2 = m.players().size() - team1;
        if (team1 != 5 || team2 != 5) {
            if (p.isShowmatch()) {
                count("showmatch maps with guests missing from stats");
            } else {
                issue("map without 5 players per team", mid + " " + team1 + "+" + team2);
            }
        }
        for (PlayerStats ps : m.players()) {
            SideStats both = ps.sides().get(0);
            if (both.rating() == null) {
                count("player lines without a rating");
            }
            if (both.kills() == null || both.deaths() == null) {
                if (p.isShowmatch()) {
                    count("showmatch player lines without kills/deaths");
                } else {
                    issue("player line without kills/deaths", mid + " " + ps.name());
                }
            }
        }
    }

    private void validateRounds(MapResult m, String mid) {
        List<RoundResult> rounds = m.rounds();
        if (rounds.isEmpty()) {
            issue("map without a round strip", mid);
            return;
        }
        RoundResult last = rounds.get(rounds.size() - 1);
        if (rounds.size() != m.team1Score() + m.team2Score()
                || last.team1Score() != m.team1Score() || last.team2Score() != m.team2Score()) {
            issue("round strip doesn't match map score", mid + " " + rounds.size() + " rounds, ends "
                    + last.team1Score() + "-" + last.team2Score() + ", map " + m.team1Score() + "-" + m.team2Score());
        }
        for (RoundResult r : rounds) {
            if (r.outcome() == null || r.winnerSide() == null) {
                count("rounds without outcome or side");
            }
        }
    }

    private void validateReplay(ParsedReplay r, List<String> unmatched) {
        String mid = r.match().matchId() + "/" + r.map().gameId();
        count("maps with replay data");
        if (!unmatched.isEmpty()) {
            issue("replay players not matched to a vlr.gg ID",
                    mid + " " + unmatched.stream().distinct().toList());
        }
        int mapRounds = r.map().team1Score() + r.map().team2Score();
        if (r.replay().rounds().size() != mapRounds) {
            issue("replay round count differs from map", mid + " replay " + r.replay().rounds().size()
                    + ", map " + mapRounds);
        }
        // The stat table counts neither spike/fall deaths (no killer) nor teamkills
        // (mostly abilities like Paint Shells), so compare enemy kills only.
        long replayKills = 0;
        for (ReplayPage.Round round : r.replay().rounds()) {
            Map<Long, Integer> team = new HashMap<>();
            round.players().forEach(pl -> team.put(pl.replayId(), pl.team()));
            for (ReplayPage.Event e : round.events()) {
                if ("kill".equals(e.type()) && e.actorId() != null) {
                    if (Objects.equals(team.get(e.actorId()), team.get(e.victimId()))) {
                        count("teamkills in replays (kept, actor_team_id = victim_team_id)");
                    } else {
                        replayKills++;
                    }
                }
            }
        }
        long statKills = r.map().players().stream()
                .map(ps -> ps.sides().get(0).kills()).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        if (replayKills != statKills && r.replay().rounds().size() == mapRounds) {
            issue("replay kills differ from stat table", mid + " replay " + replayKills + ", stats " + statKills);
        }
    }

    /** First to 13; from 12-12 overtime runs until someone leads by 2. */
    static boolean isValidMapScore(int a, int b) {
        int hi = Math.max(a, b);
        int lo = Math.min(a, b);
        return hi == 13 && lo <= 11 || hi >= 14 && hi - lo == 2;
    }

    private void issue(String check, String example) {
        issues.computeIfAbsent(check, k -> Collections.synchronizedList(new ArrayList<>())).add(example);
    }

    private void count(String what) {
        counts.merge(what, 1L, Long::sum);
    }

    private void printReport(List<MatchPage> pages, long millis, Path dbFile) throws Exception {
        long maps = pages.stream().mapToLong(p -> p.maps().size()).sum();
        Instant first = pages.stream().map(MatchPage::startTime).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant last = pages.stream().map(MatchPage::startTime).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);

        System.out.printf("%nBuilt %s in %.1fs (%d MB): %d matches, %d maps, %s to %s%n",
                dbFile, millis / 1000.0, Files.size(dbFile) / 1_000_000, pages.size(), maps, first, last);
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var st = c.createStatement()) {
            for (String table : List.of("events", "teams", "players", "matches", "maps", "player_map_stats",
                    "rounds", "round_economy", "replay_events", "replay_positions", "game_states")) {
                try (var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    System.out.printf("  %-18s %,12d rows%n", table, rs.getLong(1));
                }
            }
        }
        System.out.println();
        new ArrayList<>(counts.entrySet()).forEach(e -> System.out.printf("  %-50s %,d%n", e.getKey(), e.getValue()));

        if (issues.isEmpty()) {
            System.out.println("\nValidation: no problems found.");
            return;
        }
        System.out.println("\nValidation problems (rows are still written; check these by hand):");
        new ArrayList<>(issues.entrySet()).forEach(e -> {
            System.out.printf("  %-50s %d%n", e.getKey(), e.getValue().size());
            e.getValue().stream().limit(EXAMPLES_PER_CHECK).forEach(x -> System.out.println("      " + x));
        });
    }
}
