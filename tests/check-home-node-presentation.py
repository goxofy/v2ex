#!/usr/bin/env python3
"""Compile the production continuous header; optionally exercise it on an iOS simulator."""
from pathlib import Path
import argparse
import os
import platform
import plistlib
import subprocess
import tempfile


PROBE = r'''import SwiftUI
import UIKit

struct HomeFeed: Hashable { let title: String }
enum Theme {
    static let canvas = Color.white
    enum Metric { static let screenPadding: CGFloat = 16 }
}
enum Metrics {
    static var arrangements = 0
    static var measurements = 0
    static var placements = 0
    static var parentBodies = 0
    static var scrollRequests = 0
    static weak var window: UIWindow?
    static weak var page: PageUIView?
    static func reset() -> Void {
        arrangements = 0; measurements = 0; placements = 0; parentBodies = 0; scrollRequests = 0
    }
    static func descendants<T: UIView>(_ root: UIView, of type: T.Type) -> [T] {
        ((root as? T).map { [$0] } ?? []) + root.subviews.flatMap { descendants($0, of: type) }
    }
    static func scrollViews() -> [UIScrollView] {
        guard let window else { preconditionFailure("Missing window") }
        return descendants(window, of: UIScrollView.self)
    }
    static func topicScroll() -> UIScrollView {
        guard let scroll = scrollViews().first(where: { $0.contentSize.height > 1000 }) else {
            preconditionFailure("Missing topic scroll view")
        }
        return scroll
    }
    static func top() -> CGFloat {
        guard let page, let window else { preconditionFailure("Missing page") }
        return page.convert(page.bounds, to: window).minY
    }
    static func checkSelected() -> Void {
        guard let window else { preconditionFailure("Missing window") }
        let markers = descendants(window, of: SelectedUIView.self)
        precondition(markers.count == 2, "Both rails must update selection")
        for marker in markers {
            var ancestor = marker.superview
            while let view = ancestor, !(view is UIScrollView) { ancestor = view.superview }
            guard let scroll = ancestor as? UIScrollView else { preconditionFailure("Missing rail") }
            let rect = marker.convert(marker.bounds, to: scroll)
            precondition(scroll.bounds.intersection(rect).width >= rect.width - 1, "Selection must be fully visible")
        }
    }
    static func checkRailHit(expanded: Bool) -> Void {
        guard let window else { preconditionFailure("Missing window") }
        let rails = scrollViews().filter { $0.contentSize.width > $0.bounds.width + 1 && $0.contentSize.height < 200 }
        guard let rail = rails.first(where: { ($0.bounds.height > 60) == expanded }) else { preconditionFailure("Missing active rail") }
        let point = rail.convert(CGPoint(x: rail.bounds.midX, y: rail.bounds.minY + 18), to: window)
        guard let hit = window.hitTest(point, with: nil) else { preconditionFailure("Missing rail hit") }
        precondition(hit === rail || hit.isDescendant(of: rail), "Inactive layer must not swallow active rail gestures")
    }
    static func checkScrollEdges() -> Void {
#if PROBE_SCROLL_EDGE_EFFECTS
        if #available(iOS 26.0, *) {
            let rails = scrollViews().filter { $0.contentSize.width > $0.bounds.width + 1 && $0.contentSize.height < 200 }
            precondition(rails.count == 2, "Both rails must be present for the edge-effect check")
            let topic = topicScroll()
            for scroll in rails + [topic] {
                precondition(scroll.topEdgeEffect.isHidden, "Home must not inherit the soft top scroll edge")
            }
            precondition(!topic.bottomEdgeEffect.isHidden, "The bottom scroll edge must remain enabled")
            print("PROBE scroll edges: top hidden on both rails and topic list; bottom retained")
            return
        }
#endif
        print("SKIP: native scroll-edge assertions require both an iOS 26+ SDK and runtime")
    }
    static func checkTopicHit() -> Void {
        guard let window else { preconditionFailure("Missing window") }
        let scroll = topicScroll()
        let point = scroll.convert(CGPoint(x: 30, y: scroll.bounds.minY + 180), to: window)
        guard let hit = window.hitTest(point, with: nil) else { preconditionFailure("Missing topic hit") }
        precondition(hit === scroll || hit.isDescendant(of: scroll), "Overlay must not swallow topic gestures")
    }
}
final class PageUIView: UIView {
    override func didMoveToWindow() -> Void { Metrics.page = self; Metrics.window = window }
}
struct PageProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> PageUIView { let view = PageUIView(); view.isUserInteractionEnabled = false; return view }
    func updateUIView(_ view: PageUIView, context: Context) -> Void {}
}
final class LastRowUIView: UIView {}
struct LastRowProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> LastRowUIView { let view = LastRowUIView(); view.isUserInteractionEnabled = false; return view }
    func updateUIView(_ view: LastRowUIView, context: Context) -> Void {}
}
final class SelectedUIView: UIView {}
struct SelectedProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> SelectedUIView { let view = SelectedUIView(); view.isUserInteractionEnabled = false; return view }
    func updateUIView(_ view: SelectedUIView, context: Context) -> Void {}
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
        }.buttonStyle(.plain).background { if isSelected { SelectedProbe() } }
    }
}
struct Probe: View {
    @State private var state = HomeNodeScrollState()
    @State private var selected = HomeFeed(title: "问与答")
    @State private var width: CGFloat = 390
    @State private var feeds = ["全部", "最热", "R2", "分享创造", "分享发现", "问与答", "二手交易", "程序员", "Apple", "关注", "很长的节点名称保持完整", "职场话题", "Android", "硬件", "设计"].map { HomeFeed(title: $0) }
    var body: some View {
        Metrics.parentBodies += 1
        return HomeNodeScrollContainer(feeds: feeds, selected: $selected, state: state, content: topics) {}
            .frame(width: width, height: 700)
            .task { await exercise() }
    }
    private var topics: some View {
        TabView {
            ScrollView {
                LazyVStack {
                    ForEach(0..<100) { Text("Topic \($0)").frame(height: 60) }
                    Text("Last topic").frame(height: 60).background(LastRowProbe())
                }
            }
            .onScrollGeometryChange(for: HomeNodeScrollSample.self) { geometry in
                HomeNodeScrollSample(offset: geometry.contentOffset.y + geometry.contentInsets.top,
                                     topInset: geometry.contentInsets.top, viewportHeight: geometry.containerSize.height)
            } action: { _, sample in state.receive(sample) }
            .onScrollPhaseChange { _, phase, context in
                let geometry = context.geometry
                state.receive(HomeNodeScrollSample(offset: geometry.contentOffset.y + geometry.contentInsets.top,
                                                   topInset: geometry.contentInsets.top,
                                                   viewportHeight: geometry.containerSize.height))
                if phase == .idle { state.settle(reduceMotion: false) }
                else { state.interrupt() }
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .background(PageProbe())
    }
    @MainActor private func pause(_ milliseconds: Int = 50) async -> Void {
        try? await Task.sleep(for: .milliseconds(milliseconds))
    }
    @MainActor private func exercise() async -> Void {
        await pause(700)
        let scroll = Metrics.topicScroll()
        let size = scroll.bounds.size
        let inset = scroll.adjustedContentInset
        let expandedTop = Metrics.top()
        let rails = Metrics.scrollViews().filter { $0.contentSize.width > $0.bounds.width + 1 && $0.contentSize.height < 200 }
        precondition(rails.count == 2, "Both rails must be prelaid out")
        let railSizes = rails.map(\.contentSize)
        let railOffsets = rails.map(\.contentOffset)
        Metrics.checkRailHit(expanded: true)
        Metrics.checkScrollEdges()
        Metrics.reset()
        for offset in stride(from: 4, through: 160, by: 4) {
            scroll.setContentOffset(CGPoint(x: 0, y: CGFloat(offset) - inset.top), animated: false)
            await pause(20)
            precondition(abs(state.progress - CGFloat(offset) / 160) < 0.001, "Progress must track scroll distance")
            precondition(abs(Metrics.top() - (expandedTop - 76 * state.progress)) < 0.6, "Page must translate, not resize")
            precondition(scroll.bounds.size == size && scroll.adjustedContentInset == inset,
                         "Viewport/insets must be fixed: size=\(size)/\(scroll.bounds.size) inset=\(inset)/\(scroll.adjustedContentInset) offset=\(offset)")
        }
        for offset in stride(from: 156, through: 0, by: -4) {
            scroll.setContentOffset(CGPoint(x: 0, y: CGFloat(offset) - inset.top), animated: false)
            await pause(20)
            precondition(abs(state.progress - CGFloat(offset) / 160) < 0.001, "Reverse scroll must immediately expand")
        }
        scroll.setContentOffset(CGPoint(x: 0, y: 64 - inset.top), animated: false)
        await pause(80)
        let held = Metrics.top()
        await pause(150)
        precondition(abs(Metrics.top() - held) < 0.1 && abs(state.progress - 0.4) < 0.001, "No independent drag animation")
        state.settle(reduceMotion: false)
        await pause(40)
        precondition(state.progress > 0 && state.progress < 0.4, "Snap needs intermediate frames")
        state.interrupt()
        let interrupted = state.progress
        await pause(160)
        precondition(state.progress == interrupted, "Touch must cancel the snap at its current position")
        state.settle(reduceMotion: false)
        await pause(180)
        precondition(state.progress == 0, "Lower-half snap must expand")
        scroll.setContentOffset(CGPoint(x: 0, y: 184 - inset.top), animated: false)
        await pause(80)
        state.settle(reduceMotion: false)
        await pause(180)
        precondition(state.progress == 1, "Upper-half snap must collapse")
        Metrics.checkRailHit(expanded: false)
        Metrics.checkScrollEdges()
        precondition(rails.map(\.contentSize) == railSizes && rails.map(\.contentOffset) == railOffsets,
                     "Scroll progress must not resize or reposition either rail")
        precondition(scroll.bounds.size == size && scroll.adjustedContentInset == inset, "Snap must not resize the viewport")
        Metrics.checkTopicHit()
        print("PROBE drag/snap arrangements=\(Metrics.arrangements) measurements=\(Metrics.measurements) placements=\(Metrics.placements) parentBodies=\(Metrics.parentBodies) scrollRequests=\(Metrics.scrollRequests)")
        precondition(Metrics.arrangements == 0 && Metrics.measurements == 0 && Metrics.placements == 0,
                     "Scroll/snap must not remeasure or reflow chips")
        precondition(Metrics.parentBodies == 0 && Metrics.scrollRequests == 0, "Progress must stay out of parent/selection updates")
        scroll.setContentOffset(CGPoint(x: 0, y: scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom), animated: false)
        await pause(150)
        // Exercise the worst case: a fully expanded header at the list's bottom limit.
        state.receive(HomeNodeScrollSample(offset: 0, topInset: inset.top, viewportHeight: size.height))
        await pause(80)
        precondition(state.progress < 0.001, "Bottom reachability check needs expanded presentation: \(state.progress), height=\(size.height)/\(scroll.bounds.height)")
        guard let window = Metrics.window, let last = Metrics.descendants(window, of: LastRowUIView.self).first else {
            preconditionFailure("Last row must be materialized")
        }
        precondition(last.convert(last.bounds, to: window).maxY <= expandedTop - 116 + 700.5,
                     "Expanded fixed viewport must not hide the final row")
        selected = HomeFeed(title: "设计")
        await pause(250)
        Metrics.checkSelected()
        feeds.reverse()
        await pause(250)
        Metrics.checkSelected()
        width = 360
        await pause(250)
        Metrics.checkSelected()
        scroll.setContentOffset(CGPoint(x: 0, y: -inset.top), animated: false)
        await pause(100)
        precondition(state.progress == 0, "Top must fully expand")
        Metrics.checkTopicHit()
        feeds = [selected]
        await pause(250)
        let sparseTop = Metrics.top()
        scroll.setContentOffset(CGPoint(x: 0, y: 160 - inset.top), animated: false)
        await pause(100)
        precondition(abs(Metrics.top() - sparseTop) < 0.5, "One-row configuration must not reserve phantom rows")
        print("PROBE PASS: continuous/reverse/hold/snap/interruption/top/selection/reorder/resize/sparse/hit-testing/bottom")
        fflush(stdout)
        exit(0)
    }
}
@main final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView:
            NavigationStack {
                Probe().navigationTitle("V2EX").navigationBarTitleDisplayMode(.inline)
            }
            .softScrollEdgeEffect()
        )
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
'''


def scroll_edge_compat(root: Path, native_edges: bool) -> str:
    source = (root / 'V2EX/DesignSystem/iOS26Compat.swift').read_text()
    methods: list[str] = []
    for name in ('softScrollEdgeEffect', 'hideTopScrollEdgeEffect'):
        start = source.index(f'    @ViewBuilder\n    func {name}(')
        end = source.index('\n    }', start) + len('\n    }')
        methods.append(source[start:end])
    assert 'scrollEdgeEffectHidden(true, for: .top)' in methods[1], 'Hide only the top edge, not all edges'
    compat = 'extension View {\n' + '\n'.join(methods) + '\n}\n'
    if not native_edges:
        # Compile the real availability wrappers on older SDKs. These stand-ins
        # do not test iOS 26 rendering; the native assertions explicitly skip.
        compat += '''
enum ProbeScrollEdgeStyle { case soft }
extension View {
    func scrollEdgeEffectStyle(_ style: ProbeScrollEdgeStyle, for edges: Edge.Set) -> some View { self }
    func scrollEdgeEffectHidden(_ hidden: Bool, for edges: Edge.Set) -> some View { self }
}
'''
    return compat


def run(out: Path, device: str | None) -> None:
    root = Path(__file__).resolve().parents[1]
    source = (root / 'V2EX/Features/Home/HomeView.swift').read_text()
    header = source[source.index('private struct HomeNodeScrollContainer'):source.index('// MARK: - Community pulse')]
    assert '.onGeometryChange' not in header, 'No geometry-to-state feedback is allowed in the header'
    assert 'withAnimation' not in header and 'HomeNodeTransitionLayout' not in header
    container = header[:header.index('private struct HomeNodeRailVisibility')]
    assert '.hideTopScrollEdgeEffect()' in container, 'The home container must opt out of the inherited top blur'
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], text=True).strip()
    sdk_version = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], text=True).strip()
    native_edges = int(sdk_version.split('.')[0]) >= 26
    compat = scroll_edge_compat(root, native_edges)
    print(f'PASS: home-scoped top-only edge policy; simulator SDK {sdk_version}', flush=True)
    layout = (root / 'V2EX/Features/Home/HomeNodeLayout.swift').read_text()
    layout = layout.replace('        let sizes = sizes.map', '        Metrics.arrangements += 1\n        let sizes = sizes.map', 1)
    header = header.replace('subviews.map { $0.sizeThatFits(.unspecified) }',
                            'subviews.map { Metrics.measurements += 1; return $0.sizeThatFits(.unspecified) }')
    header = header.replace('proxy.scrollTo(', 'Metrics.scrollRequests += 1; proxy.scrollTo(')
    header = header.replace('        let layout = cache.layout', '        Metrics.placements += 1\n        let layout = cache.layout')
    (out / 'Probe.swift').write_text(PROBE + header + layout + compat)
    app = out / 'NodeProbe.app'
    app.mkdir()
    (app / 'Info.plist').write_bytes(plistlib.dumps({
        'CFBundleIdentifier': 'local.tests.node-performance', 'CFBundleExecutable': 'NodeProbe',
        'CFBundleName': 'NodeProbe', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '1.0',
        'CFBundlePackageType': 'APPL', 'MinimumOSVersion': '18.0', 'LSRequiresIPhoneOS': True,
        'UILaunchScreen': {}, 'UIDeviceFamily': [1],
    }))
    flags = ['-D', 'PROBE_SCROLL_EDGE_EFFECTS'] if native_edges else []
    subprocess.run([
        'xcrun', 'swiftc', '-parse-as-library', '-sdk', sdk, *flags,
        '-target', f'{platform.machine()}-apple-ios18.0-simulator',
        '-module-cache-path', str(out / 'module-cache'), str(out / 'Probe.swift'), '-o', str(app / 'NodeProbe'),
    ], check=True)
    if not device:
        print('PASS: production continuous header compiles (simulator execution not requested)')
        return
    subprocess.run(['xcrun', 'simctl', 'install', device, str(app)], check=True)
    try:
        result = subprocess.run([
            'xcrun', 'simctl', 'launch', '--console', device, 'local.tests.node-performance',
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        print(result.stdout)
        result.check_returncode()
        assert 'PROBE PASS:' in result.stdout, 'Simulator probe did not complete'
    finally:
        subprocess.run(['xcrun', 'simctl', 'uninstall', device, 'local.tests.node-performance'], check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', help='Booted disposable iOS 18+ simulator UUID; omit to compile only')
    args = parser.parse_args()
    job = os.environ.get('CLAUDE_JOB_DIR')
    scratch = str(Path(job) / 'tmp') if job else None
    with tempfile.TemporaryDirectory(prefix='home-node-probe-', dir=scratch) as directory:
        run(Path(directory), args.device)


if __name__ == '__main__':
    main()
