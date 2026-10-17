package dev.bernie.vct.parse;

import dev.bernie.vct.parse.MatchPage.MapResult;
import dev.bernie.vct.parse.MatchPage.PlayerStats;
import dev.bernie.vct.parse.MatchPage.RoundResult;
import dev.bernie.vct.parse.MatchPage.SideStats;
import dev.bernie.vct.parse.MatchPage.Team;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a saved vlr.gg match overview page: the match header (event, teams, series score)
 * and, per map, the map header (score, rounds by side), both teams' player stat tables,
 * and the round-by-round strip.
 */
public class MatchPageParser {

    // One tab per map, plus an "all" tab. Unplayed maps (e.g. map 3 of a 2-0) are disabled.
    static final String MAP_TAB_SELECTOR = ".vm-stats-gamesnav-item[data-game-id]";

    // One stats block per map. Used when there are no tabs, e.g. for a Bo1.
    static final String MAP_STATS_SELECTOR = ".vm-stats-game[data-game-id]";

    // Despite the name, vlr.gg's data-utc-ts is US Eastern: "13:00:00" renders as "10:00 AM PDT".
    private static final ZoneId VLR_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter VLR_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern EVENT_PATH = Pattern.compile("^/event/(\\d+)/");
    private static final Pattern TEAM_PATH = Pattern.compile("^/team/(\\d+)/");
    private static final Pattern PLAYER_PATH = Pattern.compile("^/player/(\\d+)/");
    private static final Pattern ROUND_SCORE = Pattern.compile("^(\\d+)-(\\d+)$");
    private static final Pattern ROUND_ICON = Pattern.compile("/round/([a-z]+)\\.");
    private static final Pattern PATCH = Pattern.compile("Patch\\s+(\\d+\\.\\d+)");
    private static final Pattern BEST_OF = Pattern.compile("^Bo(\\d+)$", Pattern.CASE_INSENSITIVE);

    // Sponsored names carry the plain name in parentheses: "Movistar KOI (KOI)".
    private static final Pattern SPONSORED_NAME = Pattern.compile("^.+\\((.+)\\)$");

    public List<Long> gameIds(String html) {
        return gameIds(Jsoup.parse(html));
    }

    private List<Long> gameIds(Document doc) {
        Set<Long> ids = new LinkedHashSet<>();

        for (Element tab : doc.select(MAP_TAB_SELECTOR)) {
            if (!"1".equals(tab.attr("data-disabled"))) {
                addIfNumeric(ids, tab.attr("data-game-id"));
            }
        }
        if (ids.isEmpty()) {
            for (Element block : doc.select(MAP_STATS_SELECTOR)) {
                addIfNumeric(ids, block.attr("data-game-id"));
            }
        }
        return new ArrayList<>(ids);
    }

    /** Skips the "all" pseudo-game and anything else that isn't a real ID. */
    private static void addIfNumeric(Set<Long> ids, String value) {
        if (value.matches("\\d+")) {
            ids.add(Long.parseLong(value));
        }
    }

    /** When the match was scheduled to start, or null if the page has no timestamp. */
    public Instant matchTime(String html) {
        return matchTime(Jsoup.parse(html));
    }

    private Instant matchTime(Document doc) {
        Element el = doc.selectFirst(".match-header-date [data-utc-ts]");
        if (el == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(el.attr("data-utc-ts"), VLR_TS).atZone(VLR_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses the whole page. Throws IllegalArgumentException if something build-db can't do
     * without (event, both teams, series score) is missing, so a bad page is reported, not guessed.
     */
    public MatchPage parse(long matchId, String html) {
        Document doc = Jsoup.parse(html);

        Element eventLink = require(doc.selectFirst("a.match-header-event"), "event link");
        long eventId = idFromPath(EVENT_PATH, eventLink.attr("href"), "event");
        String eventName = require(eventLink.selectFirst("div[style*=font-weight]"), "event name").text();
        Element series = eventLink.selectFirst(".match-header-event-series");
        String stage = series == null ? null : series.text();

        Element date = doc.selectFirst(".match-header-date");
        String patch = null;
        if (date != null) {
            Matcher m = PATCH.matcher(date.text());
            patch = m.find() ? m.group(1) : null;
        }

        // Tags ("100T") only appear in each map's round strip, as a pair; the first map's will do.
        Elements tags = doc.select(".vlr-rounds-row-col.mod-teams .vlr-rounds-tag");
        Team team1 = team(doc, "mod-1", tags.size() >= 2 ? tags.get(0).text() : null);
        Team team2 = team(doc, "mod-2", tags.size() >= 2 ? tags.get(1).text() : null);

        // Spans are in team order; their winner/loser class says nothing about position.
        List<Element> scores = doc.select(".match-header-vs-score "
                + "span.match-header-vs-score-winner, span.match-header-vs-score-loser");
        if (scores.size() < 2) {
            throw new IllegalArgumentException("series score not found");
        }
        int team1Score = Integer.parseInt(scores.get(0).text().trim());
        int team2Score = Integer.parseInt(scores.get(1).text().trim());

        int bestOf = 0;
        for (Element note : doc.select(".match-header-vs-note")) {
            Matcher m = BEST_OF.matcher(note.text().trim());
            if (m.matches()) {
                bestOf = Integer.parseInt(m.group(1));
            }
        }
        Element vetoEl = doc.selectFirst(".match-header-note");
        String veto = vetoEl == null || vetoEl.text().isBlank() ? null : vetoEl.text();

        List<MapResult> maps = new ArrayList<>();
        int mapNumber = 0;
        for (long gameId : gameIds(doc)) {
            Element block = doc.selectFirst(".vm-stats-game[data-game-id=" + gameId + "]");
            Element header = block == null ? null : block.selectFirst(".vm-stats-game-header");
            if (header != null) {
                maps.add(mapResult(gameId, ++mapNumber, header, block));
            }
        }

        return new MatchPage(matchId, eventId, eventName, stage, matchTime(doc), patch, bestOf,
                team1, team2, team1Score, team2Score, veto, maps);
    }

    private static Team team(Document doc, String side, String tag) {
        Element link = require(doc.selectFirst("a.match-header-link." + side), "team link " + side);
        long id = idFromPath(TEAM_PATH, link.attr("href"), "team");
        String name = require(link.selectFirst(".wf-title-med"), "team name " + side).text();
        return new Team(id, plainTeamName(name), tag == null || tag.isBlank() ? null : tag.trim());
    }

    /** "Movistar KOI (KOI)" becomes "KOI"; names without a sponsor are unchanged. */
    static String plainTeamName(String name) {
        Matcher m = SPONSORED_NAME.matcher(name.trim());
        return m.matches() ? m.group(1).trim() : name.trim();
    }

    private static MapResult mapResult(long gameId, int mapNumber, Element header, Element block) {
        List<Element> teams = header.select("> .team");
        if (teams.size() != 2) {
            throw new IllegalArgumentException("map " + gameId + ": expected 2 teams in header");
        }
        int[] t1 = sides(teams.get(0));
        int[] t2 = sides(teams.get(1));

        Element mapName = header.selectFirst(".map-name > span");
        Element picked = header.selectFirst(".map-name .picked");
        int pickedBy = picked == null ? 0 : picked.hasClass("mod-1") ? 1 : picked.hasClass("mod-2") ? 2 : 0;
        Element duration = header.selectFirst(".map-duration");
        int score1 = score(teams.get(0));
        int score2 = score(teams.get(1));

        // A real split always sums to the score; anything else means vlr.gg didn't record it.
        boolean split = t1[0] + t1[1] + t1[2] == score1 && t2[0] + t2[1] + t2[2] == score2
                && score1 + score2 > 0 && t1[3] != 0;

        return new MapResult(gameId, mapNumber,
                mapName == null ? null : mapName.ownText().trim(),
                pickedBy,
                score1, score2,
                !split ? null : t1[3] == 1 ? "atk" : "def",
                split ? t1[0] : null, split ? t1[1] : null, split ? t1[2] : null,
                split ? t2[0] : null, split ? t2[1] : null, split ? t2[2] : null,
                duration == null ? null : parseDuration(duration.text()),
                players(block), rounds(block));
    }

    // Stat columns on the overview table, by their data-col name.
    private static final String[] STAT_COLS = {"rating2", "acs", "kills", "deaths", "assists",
            "kast", "adr", "hsp", "fb", "fd"};
    private static final String[][] SIDES = {{"both", "mod-both"}, {"atk", "mod-t"}, {"def", "mod-ct"}};

    /** Two .ovw-table blocks per map: team 1's five rows, then team 2's. */
    private static List<PlayerStats> players(Element block) {
        List<PlayerStats> out = new ArrayList<>();
        Elements tables = block.select(".ovw-table");
        for (int t = 0; t < tables.size() && t < 2; t++) {
            for (Element row : tables.get(t).select(".ovw-row:not(.mod-head)")) {
                Element link = row.selectFirst(".ovw-player a[href^=/player/]");
                if (link == null) {
                    continue; // showmatch guests without a vlr.gg profile
                }
                long playerId = idFromPath(PLAYER_PATH, link.attr("href"), "player");
                Element name = row.selectFirst(".ovw-player-name");
                Element flag = row.selectFirst(".ovw-player .flag[title]");
                List<String> agents = row.select(".ovw-agents img[title]").eachAttr("title");

                List<SideStats> sides = new ArrayList<>();
                for (String[] side : SIDES) {
                    Number[] v = new Number[STAT_COLS.length];
                    for (int c = 0; c < STAT_COLS.length; c++) {
                        Element cell = row.selectFirst("[data-col=" + STAT_COLS[c] + "] .side." + side[1]);
                        v[c] = cell == null ? null : parseStat(cell.text(), c == 0);
                    }
                    sides.add(new SideStats(side[0], (Double) v[0], (Integer) v[1], (Integer) v[2],
                            (Integer) v[3], (Integer) v[4], (Integer) v[5], (Integer) v[6], (Integer) v[7],
                            (Integer) v[8], (Integer) v[9]));
                }
                out.add(new PlayerStats(playerId, name == null ? link.text() : name.text(),
                        flag == null ? null : flag.attr("title"), t + 1,
                        agents.isEmpty() ? null : String.join("/", agents), sides));
            }
        }
        return out;
    }

    /** "1.47" (rating, as Double), "85%", "+12", "173"; blank or "-" means not recorded. */
    static Number parseStat(String text, boolean decimal) {
        String t = text.replace("%", "").replace("+", "").replace("−", "-").trim();
        if (t.isEmpty() || !t.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        return decimal ? (Number) Double.parseDouble(t) : (Number) (int) Math.round(Double.parseDouble(t));
    }

    /**
     * The round strip: one column per round with the winner's square marked mod-win,
     * the winner's side as mod-t (atk) / mod-ct (def), and an icon for how it ended.
     */
    private static List<RoundResult> rounds(Element block) {
        List<RoundResult> out = new ArrayList<>();
        for (Element col : block.select(".vlr-rounds-row-col[title]")) {
            Element num = col.selectFirst(".rnd-num");
            Elements squares = col.select(".rnd-sq");
            Matcher score = ROUND_SCORE.matcher(col.attr("title"));
            if (num == null || squares.size() != 2 || !score.matches() || !num.text().trim().matches("\\d+")) {
                continue;
            }
            int winner = squares.get(0).hasClass("mod-win") ? 1 : squares.get(1).hasClass("mod-win") ? 2 : 0;
            if (winner == 0) {
                continue; // a round that wasn't played (shown as an empty column)
            }
            Element win = squares.get(winner - 1);
            Element icon = win.selectFirst("img[src]");
            Matcher how = icon == null ? null : ROUND_ICON.matcher(icon.attr("src"));
            out.add(new RoundResult(Integer.parseInt(num.text().trim()), winner,
                    win.hasClass("mod-t") ? "atk" : win.hasClass("mod-ct") ? "def" : null,
                    how != null && how.find() ? how.group(1) : null,
                    Integer.parseInt(score.group(1)), Integer.parseInt(score.group(2))));
        }
        return out;
    }

    private static int score(Element team) {
        return Integer.parseInt(require(team.selectFirst(".score"), "map score").text().trim());
    }

    /** {atk, def, ot, startSide} where startSide is 1 = atk, 2 = def, 0 = unknown. */
    private static int[] sides(Element team) {
        int[] out = new int[4];
        for (Element span : team.select("span.mod-t, span.mod-ct, span.mod-ot")) {
            int n = span.text().isBlank() ? 0 : Integer.parseInt(span.text().trim());
            if (span.hasClass("mod-ot")) {
                out[2] = n;
                continue;
            }
            boolean atk = span.hasClass("mod-t");
            out[atk ? 0 : 1] = n;
            if (out[3] == 0) {
                out[3] = atk ? 1 : 2; // the first half is listed first
            }
        }
        return out;
    }

    /** "42:06" or "1:00:03" to seconds; null if blank or odd. */
    static Integer parseDuration(String text) {
        String t = text.trim();
        if (!t.matches("\\d+(:\\d{1,2}){1,2}")) {
            return null;
        }
        int seconds = 0;
        for (String part : t.split(":")) {
            seconds = seconds * 60 + Integer.parseInt(part);
        }
        return seconds;
    }

    private static long idFromPath(Pattern pattern, String path, String what) {
        Matcher m = pattern.matcher(path);
        if (!m.find()) {
            throw new IllegalArgumentException(what + " link has no ID: " + path);
        }
        return Long.parseLong(m.group(1));
    }

    private static Element require(Element el, String what) {
        if (el == null) {
            throw new IllegalArgumentException(what + " not found");
        }
        return el;
    }

    /** True if a 2D replay page has at least one round of replay data. */
    public static boolean hasReplayData(String logsHtml) {
        return Jsoup.parse(logsHtml).selectFirst(".lg-map[data-map]") != null;
    }

    public static String logsUrl(long matchId, long gameId) {
        return ResultsPageParser.BASE_URL + "/" + matchId + "/?game=" + gameId + "&tab=logs";
    }
}
