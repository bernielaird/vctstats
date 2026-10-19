"""Small helpers in python/website/: team color parsing and .env reading.

Run from the project root: .venv\\Scripts\\python -m unittest discover -s python\\tests
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # python/
from website.export_site import COLOR_WORDS, TEAM_COLORS, color  # noqa: E402
from website.publish_site import load_env  # noqa: E402


class ColorTest(unittest.TestCase):
    def test_words_become_their_shades(self):
        self.assertEqual(color("red"), COLOR_WORDS["red"])
        self.assertEqual(color(" Light Blue "), COLOR_WORDS["light blue"])

    def test_hex_with_or_without_hash(self):
        self.assertEqual(color("#E4002B"), "#e4002b")
        self.assertEqual(color("e4002b"), "#e4002b")

    def test_blank_or_unknown_is_none(self):
        for value in ("", None, "not a color", "#12345", "#gggggg"):
            self.assertIsNone(color(value), value)

    def test_team_colors_file_is_next_to_the_exporter(self):
        self.assertTrue(os.path.exists(TEAM_COLORS), TEAM_COLORS)


class LoadEnvTest(unittest.TestCase):
    def test_reads_values_without_overriding_the_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, ".env")
            with open(path, "w", encoding="utf-8") as f:
                f.write('# comment\nVCT_TEST_A="quoted"\nVCT_TEST_B=from-file\n\nnot a setting\n')
            os.environ["VCT_TEST_B"] = "from-env"
            try:
                load_env(path)
                self.assertEqual(os.environ["VCT_TEST_A"], "quoted")
                self.assertEqual(os.environ["VCT_TEST_B"], "from-env")
            finally:
                os.environ.pop("VCT_TEST_A", None)
                os.environ.pop("VCT_TEST_B", None)


if __name__ == "__main__":
    unittest.main()
