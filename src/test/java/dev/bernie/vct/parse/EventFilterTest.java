package dev.bernie.vct.parse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFilterTest {

    // Every official event the 2023-2026 scrape found, plus a 2021 name for the older format.
    @ParameterizedTest
    @ValueSource(strings = {
            "Champions Tour 2021: Stage 3 Masters",
            "Champions Tour 2023: LOCK//IN São Paulo",
            "Champions Tour 2023: Americas League",
            "Champions Tour 2023: EMEA League",
            "Champions Tour 2023: Pacific League",
            "Champions Tour 2023: Masters Tokyo",
            "Champions Tour 2023: Champions China Qualifier",
            "Champions Tour 2023: Americas Last Chance Qualifier",
            "Valorant Champions 2023",
            "Champions Tour 2024: Pacific Kickoff",
            "Champions Tour 2024: China Stage 1",
            "Champions Tour 2024: Masters Madrid",
            "Champions Tour 2024: Masters Shanghai",
            "Valorant Champions 2024",
            "VCT 2025: Americas Kickoff",
            "VCT 2025: EMEA Stage 2",
            "Valorant Masters Bangkok 2025",
            "Valorant Masters Toronto 2025",
            "Valorant Champions 2025",
            "VCT 2026: China Stage 2",
            "Valorant Masters Santiago 2026",
            "Valorant Masters London 2026",
            "Valorant Champions 2026",
    })
    void keepsOfficialTierOneEvents(String name) {
        assertTrue(EventFilter.isWanted(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Third-party events the first version let through (79 matches in the scrape).
            "VCT OFF//SEASON Spotlight Series 2024: EMEA",
            "VCT OFF//SEASON Spotlight Series 2024: Americas",
            "China Evolution Series: Act 2 X Asian Champions League",
            "China Esports Festival Super Champions Cup",
            "HERO ESPORTS ASIAN CHAMPIONS LEAGUE 2025",
            "Shanghai Esports Masters 2024",
            "Masters Pro League - Season 3",
            // Tier 2 and Game Changers, including ones that use official naming.
            "Game Changers Championship 2025",
            "Game Changers 2026: Pacific",
            "Champions Tour 2021: Stage 1 Challengers",
            "VCT Ascension 2024: Americas",
            "Challengers 2026: North America",
            "Monsters Reloaded 2026",
            "",
    })
    void dropsEverythingElse(String name) {
        assertFalse(EventFilter.isWanted(name), name);
    }
}
