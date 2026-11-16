"""Kairos user-visible copy contract for the Android resource catalog.

Guards the split that the fork must keep:

  1. every user-visible string says "Minis Next", never the upstream product name
     — the ONLY allowed "Minis" is the upstream skill gallery ("Minis Skills",
     github.com/OpenMinis/MinisSkills) and identifiers;
  2. internal compatibility tokens are NOT renamed: `minis://`, `minis-*` CLIs,
     `/var/minis` paths, `.minisbak` backups, `com.openminis` namespace;
  3. the original project stays attributed (OpenMinis URLs untouched);
  4. the generic (built-in) sub agent description stays short: it is injected
     into the main conversation's system prompt on every turn.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src/android/app"
RES = APP / "src/main/res"
JAVA = APP / "src/main/java/com/openminis/app"

# Upstream project name that legitimately survives in copy.
UPSTREAM = "Minis Skills"

# Tokens that must keep the `minis` spelling for compatibility.
COMPAT_IN_STRINGS = [
    "minis-config",
    "minis-mcp-cli",
    "minis-model-use",
    "/var/minis",
    ".minisbak",
]


def string_values(path: Path) -> dict:
    """name -> raw text of every <string> in one strings.xml."""
    root = E.parse(path).getroot()
    out = {}
    for el in root.iter("string"):
        out[el.get("name")] = "".join(el.itertext())
    return out


def string_names(path: Path) -> list:
    return re.findall(r'<string name="([^"]+)"', path.read_text(encoding="utf-8"))


class KairosCopyTest(unittest.TestCase):
    def locales(self):
        return sorted(p for p in RES.glob("values*/strings.xml"))

    def test_all_locales_are_xml_and_have_no_duplicate_names(self):
        files = self.locales()
        self.assertEqual(23, len(files), [f.parent.name for f in files])
        for path in files:
            names = string_names(path)
            dups = {n for n in names if names.count(n) > 1}
            self.assertEqual(set(), dups, f"{path}: duplicate string names {dups}")
            # E.parse above would already have raised on malformed XML.
            self.assertTrue(len(string_values(path)) >= 1900, path)

    def test_no_user_visible_upstream_brand_left(self):
        # Every values*/*.xml: strings.xml plus the per-feature string files
        # (strings_network.xml, strings_file_browser_delete.xml, ...).
        for path in sorted(RES.glob("values*/*.xml")):
            for name, value in string_values(path).items():
                self.assertNotIn("Kairos", value, f"Old brand in {path}:{name}")
                cleaned = value.replace(UPSTREAM, "").replace("OpenMinis", "").replace("Minis Next", "")
                self.assertNotIn(
                    "Minis", cleaned,
                    f"{path.parent.name}/{path.name}:{name} still says Minis: {value!r}",
                )

    def test_compat_tokens_are_not_rebranded(self):
        base = (RES / "values/strings.xml").read_text(encoding="utf-8")
        for token in COMPAT_IN_STRINGS:
            self.assertTrue(token in base, f"compat token {token} disappeared")
        for path in self.locales():
            text = path.read_text(encoding="utf-8")
            for wrong in ("/var/kairos", ".kairosbak", "kairos://", "kairos-config", "kairos-model-use", "kairos-mcp-cli"):
                self.assertTrue(wrong not in text, f"{path.parent.name} renamed a compat token: {wrong}")

    def test_upstream_attribution_is_intact(self):
        links = (JAVA / "KairosBrandLinks.kt").read_text(encoding="utf-8")
        self.assertIn('"https://github.com/OpenMinis/OpenMinis"', links)
        self.assertIn("UPSTREAM_REPOSITORY_URL", links)
        self.assertIn("OpenMinis/MinisSkills", (JAVA / "ui/settings/MinisSkillsBrowserScreen.kt").read_text(encoding="utf-8"))

    def test_app_name_is_kairos_everywhere_it_is_defined(self):
        brand = E.parse(RES / "values/fork_branding.xml").getroot()
        self.assertEqual("Minis Next", brand.find("string[@name='fork_app_name']").text)
        self.assertEqual("Minis Next", string_values(RES / "values/strings.xml")["app_name"])

    def test_builtin_sub_agent_description_stays_short(self):
        base = string_values(RES / "values/strings.xml")["sub_agent_builtin_description"]
        self.assertEqual('Handles delegated tasks independently using tools.', base)
        self.assertLessEqual(len(base), 64, base)
        for path in self.locales():
            value = string_values(path).get('sub_agent_builtin_description')
            if value is not None:
                self.assertLessEqual(len(value), 80, (path, value))
        self.assertEqual('使用工具独立完成委派任务。',
                         string_values(RES / 'values-zh/strings.xml')['sub_agent_builtin_description'])
        self.assertEqual(1, base.count("."), base)
        src = (JAVA / "data/model/SubAgentDefinition.kt").read_text(encoding="utf-8")
        const = re.search(r'BUILT_IN_DESCRIPTION =\s*(.+?)\n\n', src, re.S).group(1)
        joined = "".join(re.findall(r'"([^"]*)"', const))
        self.assertEqual(base, joined)


if __name__ == "__main__":
    unittest.main()
