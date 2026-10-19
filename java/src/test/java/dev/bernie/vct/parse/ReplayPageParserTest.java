package dev.bernie.vct.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayPageParserTest {

    private final ReplayPageParser parser = new ReplayPageParser();

    /** Haven, map 5 of 100 Thieves 3-2 LOUD (VCT 2026 Americas Stage 2 final), 14-12. */
    private ReplayPage haven() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/logs-734308-280042.html")) {
            return parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readsEveryRound() throws Exception {
        ReplayPage page = haven();

        assertEquals(26, page.rounds().size());
        ReplayPage.Round r1 = page.rounds().get(0);
        assertEquals(1, r1.number());
        assertEquals(2, r1.atkTeam()); // LOUD attacked first
        assertEquals(10, r1.players().size());
        assertEquals(10, r1.loadouts().size());
    }

    @Test
    void firstKillHasWeaponTimeAndPositions() throws Exception {
        ReplayPage.Round r1 = haven().rounds().get(0);
        ReplayPage.Event first = r1.events().get(0);

        assertEquals("kill", first.type());
        assertEquals(14, first.timeSeconds());
        assertEquals("Ghost", first.weapon());
        assertEquals("vora", name(r1, first.actorId()));
        assertEquals("tkzin", name(r1, first.victimId()));
        assertNotNull(first.x());
        assertNotNull(first.fromX());
        // Everyone else alive: the victim isn't in the snapshot, their spot is (x, y).
        assertEquals(9, first.positions().size());
        assertTrue(first.positions().stream().noneMatch(p -> p.replayId() == first.victimId()));
        assertFalse(first.afterDetonation());
    }

    @Test
    void plantsHaveASite() throws Exception {
        long plants = 0;
        for (ReplayPage.Round r : haven().rounds()) {
            for (ReplayPage.Event e : r.events()) {
                if (e.type().equals("plant")) {
                    plants++;
                    assertTrue(e.site().matches("[ABC]"), "site " + e.site());
                    assertNull(e.victimId());
                }
            }
        }
        assertTrue(plants > 10, "plants: " + plants);
    }

    @Test
    void readsBuyPanel() throws Exception {
        ReplayPage.Loadout vora = haven().rounds().get(0).loadouts().stream()
                .filter(l -> l.name().equals("vora")).findFirst().orElseThrow();

        assertEquals("def", vora.side());
        assertEquals("Sova", vora.agent());
        assertEquals(800, vora.creditsSpent());
        assertEquals(0, vora.creditsLeft());
        assertEquals("none", vora.armor());
        assertEquals("Ghost", vora.weapon());
    }

    @Test
    void readsClockAndArmor() {
        assertEquals(96, ReplayPageParser.clock("1:36"));
        assertEquals(8, ReplayPageParser.clock("0:08"));
        assertNull(ReplayPageParser.clock(null));
        assertEquals("heavy", ReplayPageParser.armorName("Heavy armor"));
        assertEquals("regen", ReplayPageParser.armorName("Regen Shield"));
        assertEquals("none", ReplayPageParser.armorName("No armor"));
    }

    private static String name(ReplayPage.Round r, Long replayId) {
        return r.players().stream().filter(p -> p.replayId() == replayId).findFirst().orElseThrow().name();
    }
}
