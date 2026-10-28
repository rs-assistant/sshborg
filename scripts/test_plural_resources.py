"""Static resource regression; locale layout and plural selection still need device review."""
from pathlib import Path
import re
import unittest
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/res"


class PluralResourcesTest(unittest.TestCase):
    def test_million_plural_forms_exist_and_preserve_format_arguments(self):
        for locale in ("es", "fr", "it", "pt"):
            tree = ElementTree.parse(ROOT / f"values-{locale}/strings.xml")
            for key in ("sftp_cut_pending", "notification_active_sessions", "notification_bg_downloads"):
                with self.subTest(locale=locale, key=key):
                    node = tree.getroot().find(f"plurals[@name='{key}']")
                    self.assertIsNotNone(node)
                    items = {item.get("quantity"): item.text for item in node}
                    self.assertEqual(len(items), len(node), "No duplicate plural quantities")
                    self.assertTrue({"one", "many", "other"} <= items.keys())
                    self.assertTrue(items["many"])
                    self.assertEqual(re.findall(r"%(?:\d+\$)?[ds]", items["other"]),
                                     re.findall(r"%(?:\d+\$)?[ds]", items["many"]))


if __name__ == "__main__":
    unittest.main()
