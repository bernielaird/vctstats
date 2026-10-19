package dev.bernie.vct.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bernie.vct.parse.ReplayPage.Event;
import dev.bernie.vct.parse.ReplayPage.Loadout;
import dev.bernie.vct.parse.ReplayPage.Player;
import dev.bernie.vct.parse.ReplayPage.Position;
import dev.bernie.vct.parse.ReplayPage.Round;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a saved 2D replay page (?tab=logs). Each round block holds:
 *   - a JSON blob in .lg-map[data-map]: rosters, and kill/plant/defuse events with positions,
 *   - the kill feed, whose rows (data-ev = event index) add the weapon and the plant site,
 *   - the buy panel: credits, armor, and gun per player.
 * Returns no rounds for pages without replay data (all of China, a few others).
 */
public class ReplayPageParser {

    // "vora (Sova)" on the buy panel's agent icon.
    private static final Pattern AGENT_IN_TITLE = Pattern.compile("\\(([^)]+)\\)\\s*$");

    public ReplayPage parse(String html) {
        Document doc = Jsoup.parse(html);
        List<Round> rounds = new ArrayList<>();
        for (Element block : doc.select(".lg-round[data-round]")) {
            Element map = block.selectFirst(".lg-map[data-map]");
            if (map == null || !block.attr("data-round").matches("\\d+")) {
                continue;
            }
            rounds.add(round(Integer.parseInt(block.attr("data-round")),
                    JsonParser.parseString(map.attr("data-map")).getAsJsonObject(), block));
        }
        return new ReplayPage(rounds);
    }

    private static Round round(int number, JsonObject json, Element block) {
        List<Player> players = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("players").entrySet()) {
            JsonArray p = e.getValue().getAsJsonArray();
            players.add(new Player(Long.parseLong(e.getKey()), p.get(0).getAsInt(),
                    p.get(1).getAsString(), p.get(2).getAsString()));
        }

        // Kill-feed rows are keyed by event index; spike rows carry the site letter.
        Map<Integer, String> weapons = new HashMap<>();
        Map<Integer, String> sites = new HashMap<>();
        for (Element row : block.select(".lg-row[data-ev]")) {
            if (!row.attr("data-ev").matches("\\d+")) {
                continue;
            }
            int ev = Integer.parseInt(row.attr("data-ev"));
            Element source = row.selectFirst(".lg-source[title]");
            if (source != null) {
                weapons.put(ev, source.attr("title"));
            }
            Element site = row.selectFirst(".lg-site-text");
            if (site != null && !site.text().isBlank()) {
                sites.put(ev, site.text().trim());
            }
        }

        // "boom" is the index of the first event after the spike detonated.
        JsonElement boomEl = json.get("boom");
        Integer boom = boomEl == null || boomEl.isJsonNull() ? null : boomEl.getAsInt();

        List<Event> events = new ArrayList<>();
        JsonArray evs = json.getAsJsonArray("events");
        for (int i = 0; i < evs.size(); i++) {
            JsonObject e = evs.get(i).getAsJsonObject();
            double[] pos = point(e.get("pos"));
            double[] from = point(e.get("from"));
            List<Position> positions = new ArrayList<>();
            if (e.has("locs")) {
                for (JsonElement l : e.getAsJsonArray("locs")) {
                    JsonArray a = l.getAsJsonArray();
                    positions.add(new Position(a.get(0).getAsLong(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                            a.size() > 3 && !a.get(3).isJsonNull() ? (int) Math.round(a.get(3).getAsDouble()) : null));
                }
            }
            // Spike and fall deaths are logged with the victim as their own killer; there is no killer.
            Long by = lng(e, "by");
            Long victim = lng(e, "vid");
            Long actor = by != null && by.equals(victim) ? null : by;
            events.add(new Event(i, clock(str(e, "t")), str(e, "type"), actor, victim,
                    e.has("team") && !e.get("team").isJsonNull() ? e.get("team").getAsInt() : null,
                    pos == null ? null : pos[0], pos == null ? null : pos[1],
                    from == null ? null : from[0], from == null ? null : from[1],
                    weapons.get(i), sites.get(i), boom != null && i >= boom, positions));
        }

        List<Loadout> loadouts = new ArrayList<>();
        for (Element team : block.select(".lg-mir-team")) {
            String side = team.hasClass("mod-atk") ? "atk" : team.hasClass("mod-def") ? "def" : null;
            for (Element row : team.select(".lg-mir-row")) {
                Element name = row.selectFirst(".lg-mir-name");
                if (name == null) {
                    continue;
                }
                Element agentImg = row.selectFirst("img.lg-agent[title]");
                Matcher agent = agentImg == null ? null : AGENT_IN_TITLE.matcher(agentImg.attr("title"));
                Element armor = row.selectFirst(".lg-kit-armor [title]");
                Element gun = row.selectFirst(".lg-kit-gun[title]");
                loadouts.add(new Loadout(name.text().trim(), side,
                        agent != null && agent.find() ? agent.group(1) : null,
                        credits(row.selectFirst(".lg-mir-left")), credits(row.selectFirst(".lg-kit-spent")),
                        armor == null ? null : armorName(armor.attr("title")),
                        gun == null ? null : gun.attr("title")));
            }
        }

        int atk = json.has("atk") && !json.get("atk").isJsonNull() ? json.get("atk").getAsInt() : 0;
        return new Round(number, atk, players, events, loadouts);
    }

    /** "Heavy armor" -> heavy, "Light armor" -> light, "Regen Shield" -> regen, "No armor" -> none. */
    static String armorName(String title) {
        String t = title.toLowerCase();
        if (t.startsWith("heavy")) {
            return "heavy";
        }
        if (t.startsWith("light")) {
            return "light";
        }
        if (t.startsWith("regen")) {
            return "regen";
        }
        if (t.startsWith("no ")) {
            return "none";
        }
        return t;
    }

    /** "−800" or "4,250" to 800 / 4250; spent is shown negative but stored as a positive amount. */
    private static Integer credits(Element el) {
        if (el == null) {
            return null;
        }
        String digits = el.text().replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.parseInt(digits);
    }

    /** "1:36" to 96 seconds into the round. */
    static Integer clock(String t) {
        if (t == null || !t.matches("\\d+:\\d{2}")) {
            return null;
        }
        String[] p = t.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static double[] point(JsonElement el) {
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() < 2) {
            return null;
        }
        JsonArray a = el.getAsJsonArray();
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static Long lng(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : null;
    }
}
