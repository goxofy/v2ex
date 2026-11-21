#!/usr/bin/env python3
"""Compile an isolated old/new SwiftUI header probe; optionally run on a supplied simulator."""
from pathlib import Path
import argparse
import os
import platform
import plistlib
import re
import subprocess
import tempfile


def run(out: Path, baseline: str, device: str | None) -> None:
    root = Path(__file__).resolve().parents[1]
    before = subprocess.check_output(['git', 'show', f'{baseline}:V2EX/Features/Home/HomeView.swift'], text=True, cwd=root)
    after = (root / 'V2EX/Features/Home/HomeView.swift').read_text()
    old_header = before[before.index('    private var feedFilterBar:'):before.index('    @ViewBuilder\n    private func feedPage')]
    old_start = 'private struct HomeNodeBar:' if 'private struct HomeNodeBar:' in before else 'private struct HomeNodeRailIdentity:'
    old_layout = before[before.index(old_start):before.index('// MARK: - Community pulse')]
    for name in ['HomeNodeBar', 'HomeNodeFlowLayout', 'HomeNodeRailIdentity']:
        old_header = old_header.replace(name, f'Old{name}')
        old_layout = old_layout.replace(name, f'Old{name}')
    old_header = old_header.replace('withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) { nodeBarHeight = height }', 'Metrics.heightWrites += 1\n                            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) { nodeBarHeight = height }')
    new_header = after[after.index('private struct HomeNodeBar:'):after.index('// MARK: - Community pulse')]
    layout = (root / 'V2EX/Features/Home/HomeNodeLayout.swift').read_text()
    layout = layout.replace('        let sizes = sizes.map', '        Metrics.arrangements += 1\n        let sizes = sizes.map', 1)
    count_map = 'subviews.map { Metrics.measurements += 1; return $0.sizeThatFits(.unspecified) }'
    old_layout = old_layout.replace('subviews.map { $0.sizeThatFits(.unspecified) }', count_map)
    new_header = new_header.replace('subviews.map { $0.sizeThatFits(.unspecified) }', count_map)
    old_layout = old_layout.replace('proxy.scrollTo(', 'Metrics.scrollRequests += 1; proxy.scrollTo(')
    new_header = new_header.replace('proxy.scrollTo(', 'Metrics.scrollRequests += 1; proxy.scrollTo(')
    common = '''import SwiftUI
import UIKit

enum Metrics {
    static var arrangements = 0
    static var measurements = 0
    static var parentBodies = 0
    static var heightWrites = 0
    static weak var window: UIWindow?
    static var height: CGFloat = 0
    static var intermediateFrames = 0
    static var scrollRequests = 0
    static var contentWidthChanges = 0
    static var offsetJumps = 0
    static var scrolls: [ObjectIdentifier: (width: CGFloat, offset: CGFloat)] = [:]
    static func reset() -> Void {
        arrangements = 0; measurements = 0; parentBodies = 0; heightWrites = 0; intermediateFrames = 0
        scrollRequests = 0; contentWidthChanges = 0; offsetJumps = 0
    }
    static func selectedVisibility(in view: UIView) -> [Bool] {
        var results: [Bool] = []
        if view is SelectedChipUIView {
            var ancestor = view.superview
            while let current = ancestor, !(current is UIScrollView) { ancestor = current.superview }
            if let scroll = ancestor as? UIScrollView {
                let frame = view.convert(view.bounds, to: scroll)
                results.append(frame.width > 0 && scroll.bounds.intersection(frame).width >= frame.width - 1)
            }
        }
        return results + view.subviews.flatMap { selectedVisibility(in: $0) }
    }
    static func checkSelectedRails() -> Void {
        guard let window else { preconditionFailure("Missing probe window") }
        let visible = selectedVisibility(in: window)
        precondition(visible == [true, true], "Both rails must update and reveal the selected chip: \\(visible)")
    }
    static func sampleScrolls(_ view: UIView) -> Void {
        if let scroll = view as? UIScrollView, scroll.contentSize.width > scroll.bounds.width + 1,
           scroll.contentSize.height < 200 {
            let id = ObjectIdentifier(scroll)
            if let previous = scrolls[id] {
                if abs(previous.width - scroll.contentSize.width) > 1 { contentWidthChanges += 1 }
                if abs(previous.offset - scroll.contentOffset.x) > 40 { offsetJumps += 1 }
            }
            scrolls[id] = (scroll.contentSize.width, scroll.contentOffset.x)
        }
        view.subviews.forEach { sampleScrolls($0) }
    }
}
final class ProbeUIView: UIView {
    private var displayLink: CADisplayLink?
    override func didMoveToWindow() -> Void {
        displayLink?.invalidate()
        displayLink = nil
        if window != nil {
            Metrics.window = window
            let link = CADisplayLink(target: self, selector: #selector(sample))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
    }
    @objc private func sample() -> Void {
        if let window { Metrics.sampleScrolls(window) }
        let height = layer.presentation()?.bounds.height ?? bounds.height
        if height > 40.5 && height < 115.5 { Metrics.intermediateFrames += 1 }
    }
}
struct FrameProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> ProbeUIView { ProbeUIView() }
    func updateUIView(_ view: ProbeUIView, context: Context) -> Void {}
}
struct HomeFeed: Hashable {
    let title: String
    static let all = HomeFeed(title: "问与答")
}
@MainActor final class HomeViewModel: ObservableObject { @Published var feed = HomeFeed.all }
enum Theme { enum Metric { static let screenPadding: CGFloat = 16 } }
extension View { func readableColumn() -> some View { self } }
final class SelectedChipUIView: UIView {}
struct SelectedChipProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> SelectedChipUIView { SelectedChipUIView() }
    func updateUIView(_ view: SelectedChipUIView, context: Context) -> Void {}
}
struct FilterChip: View {
    let title: String
    let isSelected: Bool
    var minimumHeight: CGFloat = 44
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 14, weight: isSelected ? .semibold : .medium))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(.regularMaterial, in: Capsule()).frame(minHeight: minimumHeight)
        }.buttonStyle(.plain)
            .background { if isSelected { SelectedChipProbe() } }
    }
}
struct Probe: View {
    @StateObject private var model = HomeViewModel()
    @State private var editingTabs = false
    @State private var nodeBarHeight: CGFloat = 112
    @State private var nodesCollapsed = false
    @Environment(\\.accessibilityReduceMotion) private var reduceMotion
    @State private var feeds = ["全部", "最热", "R2", "分享创造", "分享发现", "问与答", "二手交易", "程序员", "Apple", "关注", "很长的节点名称保持完整", "职场话题", "Android", "硬件", "设计"].map { HomeFeed(title: $0) }
    private let old = CommandLine.arguments.contains("before")
    var body: some View {
        Metrics.parentBodies += 1
        return ScrollView { LazyVStack { ForEach(0..<100) { Text("Topic \\($0)").frame(height: 60) } } }
            .safeAreaInset(edge: .top, spacing: 0) {
                Group {
                    if old { feedFilterBar }
                    else { HomeNodeBar(feeds: feeds, selected: $model.feed, collapsed: nodesCollapsed) { editingTabs = true } }
                }
                .background(FrameProbe())
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { Metrics.height = $0 }
            }
            .task {
                try? await Task.sleep(for: .seconds(1))
                print("PROBE initial height=\\(Metrics.height)")
                Metrics.reset()
                for _ in 0..<10 {
                    withAnimation(.easeInOut(duration: 0.22)) { nodesCollapsed.toggle() }
                    try? await Task.sleep(for: .milliseconds(400))
                    print("PROBE collapsed=\\(nodesCollapsed) height=\\(Metrics.height)")
                }
                print("PROBE result before=\\(old) arrangements=\\(Metrics.arrangements) measurements=\\(Metrics.measurements) parentBodies=\\(Metrics.parentBodies) heightWrites=\\(Metrics.heightWrites) intermediateFrames=\\(Metrics.intermediateFrames)")
                print("PROBE scrollRequests=\\(Metrics.scrollRequests) contentWidthChanges=\\(Metrics.contentWidthChanges) offsetJumps=\\(Metrics.offsetJumps)")
                if !old {
                    Metrics.reset()
                    model.feed = HomeFeed(title: "设计")
                    try? await Task.sleep(for: .milliseconds(400))
                    precondition(Metrics.scrollRequests == 2, "Selection must reposition both rails")
                    Metrics.checkSelectedRails()
                    Metrics.reset()
                    feeds.reverse()
                    try? await Task.sleep(for: .milliseconds(400))
                    precondition(Metrics.scrollRequests == 2, "Configuration must reposition both rails")
                    Metrics.checkSelectedRails()
                    Metrics.reset()
                    for _ in 0..<6 {
                        withAnimation(.easeInOut(duration: 0.22)) { nodesCollapsed.toggle() }
                        try? await Task.sleep(for: .milliseconds(80))
                    }
                    try? await Task.sleep(for: .milliseconds(400))
                    precondition(abs(Metrics.height - 116) < 0.5, "Interrupted transitions must settle expanded")
                    precondition(Metrics.intermediateFrames > 0, "Interrupted transitions must remain animated")
                    precondition(Metrics.scrollRequests == 0 && Metrics.contentWidthChanges == 0 && Metrics.offsetJumps == 0,
                                 "Reversing a transition must not disturb either scroll view")
                    Metrics.checkSelectedRails()
                    print("PROBE interaction selection/reorder/reversal passed")
                }
                fflush(stdout)
                exit(0)
            }
    }
'''
    entry = '''
@main final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView: Probe())
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
'''
    (out / 'Probe.swift').write_text(common + old_header + '}\n' + old_layout + new_header + layout + entry)
    app = out / 'NodeProbe.app'
    app.mkdir(exist_ok=True)
    (app / 'Info.plist').write_bytes(plistlib.dumps({
        'CFBundleIdentifier': 'local.tests.node-performance', 'CFBundleExecutable': 'NodeProbe',
        'CFBundleName': 'NodeProbe', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '1.0',
        'CFBundlePackageType': 'APPL', 'MinimumOSVersion': '18.0', 'LSRequiresIPhoneOS': True,
        'UILaunchScreen': {}, 'UIDeviceFamily': [1],
    }))
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], text=True).strip()
    subprocess.run([
        'xcrun', 'swiftc', '-parse-as-library', '-sdk', sdk,
        '-target', f'{platform.machine()}-apple-ios18.0-simulator',
        '-module-cache-path', str(out / 'module-cache'), str(out / 'Probe.swift'),
        '-o', str(app / 'NodeProbe'),
    ], check=True)
    if not device:
        print('PASS: extracted old/new header expressions compile (no simulator execution requested)')
        return
    subprocess.run(['xcrun', 'simctl', 'install', device, str(app)], check=True)
    try:
        results: list[tuple[int, ...]] = []
        heights: list[list[str]] = []
        scroll_results: list[tuple[int, ...]] = []
        for variant in ['before', 'after']:
            output = subprocess.check_output([
                'xcrun', 'simctl', 'launch', '--console', device, 'local.tests.node-performance', variant,
            ], text=True, stderr=subprocess.STDOUT, timeout=90)
            print('\n'.join(line for line in output.splitlines() if line.startswith('PROBE')))
            match = re.search(r'arrangements=(\d+) measurements=(\d+) parentBodies=(\d+) heightWrites=(\d+) intermediateFrames=(\d+)', output)
            if match is None:
                raise RuntimeError(f'{variant} probe did not finish: {output}')
            results.append(tuple(int(value) for value in match.groups()))
            heights.append(re.findall(r'PROBE collapsed=\w+ height=([\d.]+)', output))
            scroll_match = re.search(r'scrollRequests=(\d+) contentWidthChanges=(\d+) offsetJumps=(\d+)', output)
            if scroll_match is None:
                raise RuntimeError('Missing scroll-transition metrics')
            scroll_results.append(tuple(int(value) for value in scroll_match.groups()))
            if variant == 'after':
                assert 'PROBE interaction selection/reorder/reversal passed' in output, output
        assert len(heights[0]) == 10 and heights[0] == heights[1], heights
        assert scroll_results[1] == (0, 0, 0), scroll_results
        assert results[1][0] <= results[0][0] and results[1][1] <= results[0][1], results
        assert results[1][2] <= results[0][2] and results[1][3] == 0, results
        assert results[1][4] > 0, 'Optimization must retain animated intermediate heights'
        print('PASS: same endpoint heights, animated intermediate frames, stable scroll geometry, '
              'no transition scroll requests, bounded layout work, selection/reorder/reversal checks')
    finally:
        subprocess.run(['xcrun', 'simctl', 'uninstall', device, 'local.tests.node-performance'], check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='bf70b9e', help='Pre-transition-fix commit')
    parser.add_argument('--device', help='Booted disposable iOS 18+ simulator UUID; omit to compile only')
    args = parser.parse_args()
    job = os.environ.get('CLAUDE_JOB_DIR')
    scratch = str(Path(job) / 'tmp') if job else None
    with tempfile.TemporaryDirectory(prefix='home-node-probe-', dir=scratch) as directory:
        run(Path(directory), args.baseline, args.device)


if __name__ == '__main__':
    main()
