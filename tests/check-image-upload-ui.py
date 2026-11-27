#!/usr/bin/env python3
"""Run isolated iOS 18+ editor/picker UI tests without contacting Imgur or V2EX.

Uses production upload/selection views with fixture settings, theme and transport.
This is not a full application build or an iOS 26 visual acceptance test.
Requires Xcode, xcodegen and an available iOS 18+ simulator.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    "Networking/ImgurImageLink.swift",
    "Networking/ImgurUploadClient.swift",
    "Features/Compose/ImageTextInsertion.swift",
    "Features/Compose/ImageEditorSelection.swift",
    "Features/Compose/ImageUploadCoordinator.swift",
    "Features/Compose/UploadImagePreparation.swift",
    "Features/Compose/ImageUploadButton.swift",
    "Features/Settings/ImgurSettingsView.swift",
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="An available iOS 18+ simulator UUID")
    args = parser.parse_args()
    if shutil.which("xcodegen") is None:
        parser.error("xcodegen is required")
    job_dir = os.environ.get("CLAUDE_JOB_DIR")
    temporary_root = str(Path(job_dir) / "tmp") if job_dir else None
    with tempfile.TemporaryDirectory(prefix="imgur-ui-", dir=temporary_root) as folder:
        work = Path(folder)
        (work / "UITests").mkdir()
        fixtures = ROOT / "tests/fixtures/image-upload-ui"
        shutil.copy2(fixtures / "Probe.swift", work / "Probe.swift")
        shutil.copy2(fixtures / "ProbeUITests.swift", work / "UITests/ProbeUITests.swift")
        spec = {
            "name": "ImgurProbe",
            "options": {"deploymentTarget": {"iOS": "18.0"}},
            "settings": {"base": {
                "SWIFT_VERSION": "5.0", "CURRENT_PROJECT_VERSION": "1", "MARKETING_VERSION": "1.0",
                "CODE_SIGNING_ALLOWED": False, "GENERATE_INFOPLIST_FILE": True, "TARGETED_DEVICE_FAMILY": "1",
            }},
            "targets": {
                "ImgurProbe": {
                    "type": "application", "platform": "iOS",
                    "sources": ["Probe.swift"] + [str(ROOT / "V2EX" / source) for source in SOURCES],
                    "settings": {"base": {"PRODUCT_BUNDLE_IDENTIFIER": "local.imgur.probe", "INFOPLIST_KEY_UILaunchScreen_Generation": True}},
                },
                "ImgurProbeUITests": {
                    "type": "bundle.ui-testing", "platform": "iOS", "sources": ["UITests"],
                    "dependencies": [{"target": "ImgurProbe"}],
                    "settings": {"base": {"PRODUCT_BUNDLE_IDENTIFIER": "local.imgur.probe.uitests", "TEST_TARGET_NAME": "ImgurProbe"}},
                },
            },
            "schemes": {"ImgurProbe": {
                "build": {"targets": {"ImgurProbe": "all"}},
                "test": {"targets": ["ImgurProbeUITests"], "gatherCoverageData": False},
            }},
        }
        spec_path = work / "project.json"
        spec_path.write_text(json.dumps(spec), encoding="utf-8")
        subprocess.run(["xcodegen", "generate", "--spec", str(spec_path), "--project", str(work)], check=True)
        subprocess.run([
            "xcodebuild", "test", "-project", str(work / "ImgurProbe.xcodeproj"), "-scheme", "ImgurProbe",
            "-destination", f"platform=iOS Simulator,id={args.device}", "-derivedDataPath", str(work / "build"),
            "-parallel-testing-enabled", "NO", "-maximum-concurrent-test-simulator-destinations", "1",
        ], check=True)


if __name__ == "__main__":
    main()
