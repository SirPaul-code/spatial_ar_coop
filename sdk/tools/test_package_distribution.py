#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class PackageDistributionTest(unittest.TestCase):
    def test_bundle_is_traceable_and_never_self_clears_commercial_release(self):
        script = Path(__file__).with_name("package_distribution.py")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "repo"
            unity = repo / "sdk" / "unity"
            unity.mkdir(parents=True)
            (unity / "package.json").write_text('{"name":"com.stablear.sdk"}\n', encoding="utf-8")
            output = root / "bundle"
            subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "--version",
                    "0.2.0-preview.test",
                    "--output",
                    str(output),
                    "--repo-root",
                    str(repo),
                    "--source-sha",
                    "0123456789abcdef",
                    "--platform",
                    "unity",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["source_sha"], "0123456789abcdef")
            self.assertEqual(manifest["platforms"], ["unity"])
            self.assertFalse(manifest["commercial_release_cleared"])
            self.assertEqual(len(manifest["artifacts"]), 1)
            self.assertEqual(manifest["artifacts"][0]["path"], "unity/package.json")
            self.assertTrue((output / "SHA256SUMS").is_file())


if __name__ == "__main__":
    unittest.main()
