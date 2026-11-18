"""Static UI-scope regression check, not a Compose or device test."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/src/android/app/src/main/res"


class OwnKeyRefreshLabel(unittest.TestCase):
    def test_label_is_scoped_and_uses_existing_localized_resources(self):
        source = (ROOT / "android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt").read_text()
        start = source.index("// nanoMuse: localize only an own-key provider")
        end = source.index("onClick = if (isRefreshing)", start)
        block = source[start:end]
        for guard in ("nmParallel && storedKey.isNotBlank()", "ProviderCredential.apiKey",
                      "NanoMuseCloud.instance(exportContext)?.id?.let { it != instanceId } == true"):
            self.assertIn(guard, block)
        self.assertIn("stringResource(R.string.nm_cloud_refreshing)", block)
        self.assertIn("stringResource(R.string.provider_detail_refresh_models)", block)
        self.assertIn('else if (isRefreshing) "Refreshing…" else "Refresh model list"', block)
        portuguese = {}
        for file in ("nm_strings.xml", "strings.xml"):
            tree = ET.parse(RES / "values-pt-rBR" / file)
            portuguese.update({node.attrib["name"]: node.text for node in tree.findall("string")})
        self.assertEqual("Atualizando…", portuguese["nm_cloud_refreshing"])
        self.assertEqual("Atualizar modelos", portuguese["provider_detail_refresh_models"])


if __name__ == "__main__":
    unittest.main()
