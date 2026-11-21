#!/usr/bin/env python3
"""Run the production networking XCTest suites without the iOS 26 UI SDK."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    job_dir = os.environ.get("CLAUDE_JOB_DIR")
    scratch = str(Path(job_dir) / "tmp") if job_dir else None
    with tempfile.TemporaryDirectory(prefix="v2ex-reply-tests-", dir=scratch) as directory:
        package = Path(directory)
        sources = package / "Sources" / "V2EX"
        tests = package / "Tests" / "V2EXTests"
        sources.mkdir(parents=True)
        tests.mkdir(parents=True)
        for name in ["Networking/V2EXClient.swift", "Networking/ReplySubmission.swift",
                     "Networking/HTMLContent.swift", "Models/Models.swift",
                     "Networking/TopicMovePage.swift", "Networking/TopicMoveService.swift",
                     "Storage/TopicMoveStore.swift", "Storage/V2EXSessionStore.swift",
                     "Storage/HomeTabsStore.swift", "Features/Nodes/NodeCatalog.swift",
                     "Networking/FollowingService.swift", "Features/Home/HomeViewModel.swift",
                     "Features/Home/HomeRefreshFeedback.swift"]:
            shutil.copy2(root / "V2EX" / name, sources)
        for name in ["ReplySubmissionTests.swift", "NodeFollowingTests.swift", "TopicMoveTests.swift", "TopicMoveStoreTests.swift", "HomeTabsTests.swift", "FollowingTests.swift"]:
            shutil.copy2(root / "V2EXTests" / name, tests)
        (package / "Package.swift").write_text('''// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "V2EXNetworkingChecks",
    platforms: [.macOS(.v14)],
    targets: [.target(name: "V2EX"), .testTarget(name: "V2EXTests", dependencies: ["V2EX"])]
)
''')
        env = dict(os.environ, V2EX_MOVE_HTTP_FIXTURE=str(root / "tests/topic-move-http-fixture.py"))
        subprocess.run(["swift", "test", "--package-path", str(package)], check=True, env=env)


if __name__ == "__main__":
    main()
