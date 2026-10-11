"""Fail-closed APK signing contract (does not inspect or expose private keys)."""
import re
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
EXPECTED="6af53b6b3e2574eed16aaae7ce92ade3dcde702a74af1dfae994c9c87609cbb6"

class SigningContractTests(unittest.TestCase):
    def test_pinned_cert_is_from_original_newal_release(self):
        self.assertEqual((ROOT/"android/signing-cert-sha256.txt").read_text().strip(),EXPECTED)
    def test_debug_package_is_separate_from_release(self):
        s=(ROOT/"app/build.gradle").read_text()
        self.assertIn("applicationId 'dev.newal.code.lite.connectors'",s)
        self.assertIn("applicationIdSuffix '.m4repair'",s)
        self.assertNotIn("applicationIdSuffix '.m4dev'",s)
        self.assertIn("10000",s)
        self.assertNotIn("signingConfig signingConfigs.debug",s)
    def test_no_unsigned_update_published(self):
        script=(ROOT/"scripts/build-signed-release.sh").read_text()
        workflow=(ROOT/".github/workflows/android.yml").read_text()
        self.assertIn("MUSABAI_RELEASE_SIGNING_BUNDLE",script)
        self.assertIn("MUSABAI_RELEASE_SIGNING_BUNDLE",workflow)
        self.assertIn("signing-cert-sha256.txt",script)
        self.assertIn('if [ "$actual" != "$expected" ]',script)
        self.assertIn("if: steps.persistent_signing.outputs.available == 'true'",workflow)
    def test_private_signing_files_are_ignored(self):
        ignore=(ROOT/".gitignore").read_text()
        for ext in ("*.jks","*.keystore","*.p12","*.pfx"):
            self.assertIn(ext,ignore)

if __name__ == "__main__": unittest.main()
