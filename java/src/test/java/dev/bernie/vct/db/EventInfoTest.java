package dev.bernie.vct.db;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventInfoTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Champions Tour 2023: LOCK//IN São Paulo          | 2023 | masters   | International",
            "Champions Tour 2023: Americas League             | 2023 | league    | Americas",
            "Champions Tour 2023: Masters Tokyo               | 2023 | masters   | International",
            "Champions Tour 2023: EMEA Last Chance Qualifier  | 2023 | lcq       | EMEA",
            "Champions Tour 2023: Champions China Qualifier   | 2023 | qualifier | China",
            "Champions Tour 2024: Pacific Kickoff             | 2024 | kickoff   | Pacific",
            "Champions Tour 2024: China Stage 2               | 2024 | league    | China",
            "Champions Tour 2024: Masters Shanghai            | 2024 | masters   | International",
            "VCT 2025: EMEA Stage 1                           | 2025 | league    | EMEA",
            "Valorant Masters Toronto 2025                    | 2025 | masters   | International",
            "Valorant Champions 2026                          | 2026 | champions | International",
    })
    void classifiesEveryKindOfEvent(String name, int year, String type, String region) {
        assertEquals(new EventInfo(year, type, region), EventInfo.of(name.trim()));
    }
}
