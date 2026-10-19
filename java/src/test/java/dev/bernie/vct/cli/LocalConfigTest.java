package dev.bernie.vct.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalConfigTest {

    @TempDir
    Path dir;

    @Test
    void readsKeyValueLinesAndSkipsComments() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
            # contact for vlr.gg
            VCT_TEST_ONLY_A = someone@example.com
            VCT_TEST_ONLY_B="quoted@example.com"
            VCT_TEST_ONLY_EMPTY=
            not a setting
            """);

        LocalConfig config = new LocalConfig(env);

        assertEquals("someone@example.com", config.get("VCT_TEST_ONLY_A"));
        assertEquals("quoted@example.com", config.get("VCT_TEST_ONLY_B"));
        assertNull(config.get("VCT_TEST_ONLY_EMPTY"));
        assertNull(config.get("VCT_TEST_ONLY_MISSING"));
    }

    @Test
    void missingFileIsFine() throws Exception {
        assertNull(new LocalConfig(dir.resolve("nope.env")).get("VCT_TEST_ONLY_A"));
    }
}
