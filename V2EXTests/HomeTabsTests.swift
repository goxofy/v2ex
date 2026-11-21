import XCTest
@testable import V2EX

@MainActor
final class HomeTabsTests: XCTestCase {
    func testFlowUsesNaturalWidthsAndConfigurationOrder() -> Void {
        let sizes = [50, 90, 70, 120, 40].map { CGSize(width: $0, height: 36) }
        let layout = HomeNodeLayout(sizes: sizes, viewportWidth: 160, maxRows: 3)
        XCTAssertEqual(layout.frames.map(\.size), sizes)
        XCTAssertEqual(layout.frames.map(\.minX), [0, 56, 0, 0, 126])
        XCTAssertEqual(layout.frames.map(\.minY), [0, 0, 38, 76, 76])
        XCTAssertEqual(layout.size.height, 112)
    }

    func testFlowOverflowKeepsAllFifteenChipsWithinThreeRows() -> Void {
        let sizes = (0..<15).map { CGSize(width: 50 + $0 * 13, height: 36) }
        let layout = HomeNodeLayout(sizes: sizes, viewportWidth: 240, maxRows: 3)
        XCTAssertGreaterThan(layout.size.width, 240)
        XCTAssertEqual(layout.frames.map(\.size), sizes)
        XCTAssertLessThanOrEqual(Set(layout.frames.map(\.minY)).count, 3)
        for (previous, current) in zip(layout.frames, layout.frames.dropFirst()) {
            XCTAssertTrue(current.minY > previous.minY || current.minX > previous.maxX)
        }
        XCTAssertTrue(layout.frames.allSatisfy { $0.maxX <= layout.size.width })
    }

    func testVeryLongChipIsNotTruncated() -> Void {
        let layout = HomeNodeLayout(sizes: [CGSize(width: 900, height: 36), CGSize(width: 45, height: 36)],
                                    viewportWidth: 240, maxRows: 3)
        XCTAssertEqual(layout.frames.first?.width, 900)
        XCTAssertGreaterThanOrEqual(layout.size.width, 900)
        XCTAssertEqual(layout.frames.last?.minY, 38)
    }

    func testCollapsedFlowIsOneNaturalWidthRow() -> Void {
        let sizes = [50, 100, 65, 200].map { CGSize(width: $0, height: 36) }
        let layout = HomeNodeLayout(sizes: sizes, viewportWidth: 160, maxRows: 1)
        XCTAssertEqual(layout.frames.map(\.size), sizes)
        XCTAssertTrue(layout.frames.allSatisfy { $0.minY == 0 })
        XCTAssertEqual(layout.size.height, 36)
        XCTAssertEqual(layout.frames.map(\.minX), [0, 56, 162, 233])
    }

    func testShortFlowDoesNotReserveThreeRows() -> Void {
        let layout = HomeNodeLayout(sizes: [CGSize(width: 50, height: 36)], viewportWidth: 320, maxRows: 3)
        XCTAssertEqual(layout.size.height, 36)
        XCTAssertEqual(HomeNodeLayout(sizes: [], viewportWidth: 320, maxRows: 3).size.height, 0)
    }

    func testFlowHandlesInvalidMetricsAndVariableHeights() -> Void {
        let layout = HomeNodeLayout(sizes: [CGSize(width: 60, height: 40), CGSize(width: 60, height: 60),
                                           CGSize(width: 60, height: 36)], viewportWidth: 126, maxRows: 3)
        XCTAssertEqual(layout.frames.last?.minY, 62)
        let invalid = HomeNodeLayout(sizes: [CGSize(width: CGFloat.nan, height: -1)],
                                     viewportWidth: .infinity, maxRows: 0)
        XCTAssertEqual(invalid.frames.first?.size, .zero)
        XCTAssertTrue(invalid.size.width.isFinite)
    }

    func testRepeatedLayoutPassesReuseBothArrangements() -> Void {
        let sizes = (0..<15).map { CGSize(width: 50 + $0 * 13, height: 36) }
        var cache = HomeNodeLayoutCache(sizes: sizes)
        let expanded = cache.layout(viewportWidth: 320, maxRows: 3)
        let collapsed = cache.layout(viewportWidth: 320, maxRows: 1)
        for _ in 0..<240 {
            cache.update(sizes: sizes)
            XCTAssertTrue(cache.layout(viewportWidth: 320, maxRows: 3) === expanded)
            XCTAssertTrue(cache.layout(viewportWidth: 320, maxRows: 1) === collapsed)
        }
        XCTAssertEqual(expanded.frames, HomeNodeLayout(sizes: sizes, viewportWidth: 320, maxRows: 3).frames)
        XCTAssertEqual(collapsed.frames, HomeNodeLayout(sizes: sizes, viewportWidth: 320, maxRows: 1).frames)
    }

    func testLayoutCacheInvalidatesForChangedContentAndWidth() -> Void {
        var cache = HomeNodeLayoutCache(sizes: [CGSize(width: 60, height: 36)])
        let first = cache.layout(viewportWidth: 320, maxRows: 3)
        cache.update(sizes: [CGSize(width: 80, height: 40)])
        let changed = cache.layout(viewportWidth: 320, maxRows: 3)
        XCTAssertFalse(first === changed)
        XCTAssertEqual(changed.frames.first?.size, CGSize(width: 80, height: 40))
        let rotated = cache.layout(viewportWidth: 500, maxRows: 3)
        XCTAssertFalse(changed === rotated)
        XCTAssertGreaterThanOrEqual(rotated.size.width, 500)
        cache.update(sizes: [])
        XCTAssertEqual(cache.layout(viewportWidth: 500, maxRows: 3).frames, [])
    }

    func testLayoutCachesAreIndependentAndPreserveReorderedChips() -> Void {
        let sizes = [CGSize(width: 60, height: 36), CGSize(width: 90, height: 36)]
        var first = HomeNodeLayoutCache(sizes: sizes)
        var second = HomeNodeLayoutCache(sizes: sizes)
        XCTAssertFalse(first.layout(viewportWidth: 320, maxRows: 3) === second.layout(viewportWidth: 320, maxRows: 3))
        first.update(sizes: sizes.reversed())
        XCTAssertEqual(first.layout(viewportWidth: 320, maxRows: 3).frames.map(\.size), sizes.reversed())
        XCTAssertEqual(second.layout(viewportWidth: 320, maxRows: 3).frames.map(\.size), sizes)
    }

    func testTransitionPreservesEndpointsAndInterpolatesHeight() -> Void {
        XCTAssertEqual(HomeNodeTransition(progress: 0).height(expanded: 116, collapsed: 40), 116)
        XCTAssertEqual(HomeNodeTransition(progress: 1).height(expanded: 116, collapsed: 40), 40)
        XCTAssertEqual(HomeNodeTransition(progress: 0.5).height(expanded: 116, collapsed: 40), 78)
        for step in 0...100 {
            let progress = CGFloat(step) / 100
            XCTAssertEqual(HomeNodeTransition(progress: progress).height(expanded: 40, collapsed: 40), 40)
        }
    }

    func testTransitionClampsInvalidProgress() -> Void {
        for progress: CGFloat in [-1, -.infinity, .infinity, .nan] {
            XCTAssertEqual(HomeNodeTransition(progress: progress).height(expanded: 116, collapsed: 40), 116)
        }
        XCTAssertEqual(HomeNodeTransition(progress: 2).height(expanded: 116, collapsed: 40), 40)
    }

    func testTransitionCanReverseWithoutChangingRailGeometry() -> Void {
        let sizes = (0..<15).map { CGSize(width: 50 + $0 * 13, height: 36) }
        var expandedCache = HomeNodeLayoutCache(sizes: sizes)
        var collapsedCache = HomeNodeLayoutCache(sizes: sizes)
        let expanded = expandedCache.layout(viewportWidth: 320, maxRows: 3)
        let collapsed = collapsedCache.layout(viewportWidth: 320, maxRows: 1)
        let steps = Array(0...60) + Array((0...60).reversed())
        var previous = expanded.size.height
        for step in steps {
            let height = HomeNodeTransition(progress: CGFloat(step) / 100)
                .height(expanded: expanded.size.height, collapsed: collapsed.size.height)
            XCTAssertLessThanOrEqual(abs(height - previous), 0.761)
            XCTAssertTrue(expandedCache.layout(viewportWidth: 320, maxRows: 3) === expanded)
            XCTAssertTrue(collapsedCache.layout(viewportWidth: 320, maxRows: 1) === collapsed)
            previous = height
        }
        XCTAssertEqual(previous, expanded.size.height)
    }

    func testCollapseUsesSeparateScrollThresholds() -> Void {
        let previous = HomeNodeScrollSample(offset: 0, topInset: 112, viewportHeight: 600)
        for (offset, collapsed, expected): (CGFloat, Bool, Bool) in [
            (120, false, false), (121, false, true), (50, true, true),
            (45, true, true), (84, false, false),
            (9, true, true), (8, true, false), (-30, true, false)
        ] {
            let sample = HomeNodeScrollSample(offset: offset, topInset: 112, viewportHeight: 600)
            XCTAssertEqual(sample.collapsed(after: previous, wasCollapsed: collapsed), expected)
        }
    }

    func testPixelScrollOnlyChangesCollapseStateAtThresholds() -> Void {
        var previous = HomeNodeScrollSample(offset: 0, topInset: 112, viewportHeight: 600)
        var collapsed = false
        var writes = 0
        for offset in Array(0...1000) + Array((0...1000).reversed()) {
            let sample = HomeNodeScrollSample(offset: CGFloat(offset), topInset: 112, viewportHeight: 600)
            let next = sample.collapsed(after: previous, wasCollapsed: collapsed)
            if next != collapsed { writes += 1; collapsed = next }
            previous = sample
        }
        XCTAssertEqual(writes, 2)
        XCTAssertFalse(collapsed)
    }

    func testHeaderResizeCannotTriggerCollapseOscillation() -> Void {
        let previous = HomeNodeScrollSample(offset: 90, topInset: 112, viewportHeight: 600)
        let resized = HomeNodeScrollSample(offset: 0, topInset: 40, viewportHeight: 672)
        XCTAssertTrue(resized.collapsed(after: previous, wasCollapsed: true))
        XCTAssertFalse(previous.collapsed(after: resized, wasCollapsed: false))
        let invalid = HomeNodeScrollSample(offset: .nan, topInset: 112, viewportHeight: 600)
        XCTAssertTrue(invalid.collapsed(after: previous, wasCollapsed: true))
    }

    func testMigrationPreservesVisibleNodesAndDoesNotFollowLaterSync() -> Void {
        let key = "HomeTabsTests.\(UUID())"
        let defaults = UserDefaults(suiteName: key)!
        defer { defaults.removePersistentDomain(forName: key) }
        defaults.set(["apple", "qna"], forKey: "followedNodes")
        let store = HomeTabsStore(preferences: defaults)
        XCTAssertEqual(store.items.map(\.id), ["all", "hot", "r2", "following", "node:apple", "node:qna"])
        defaults.set(["other"], forKey: "followedNodes")
        XCTAssertEqual(HomeTabsStore(preferences: defaults).items, store.items)
    }

    func testAllTabsCanBeOrderedRemovedAndPersisted() -> Void {
        let key = "HomeTabsTests.\(UUID())"
        let defaults = UserDefaults(suiteName: key)!
        defer { defaults.removePersistentDomain(forName: key) }
        let store = HomeTabsStore(preferences: defaults)
        store.save([.following, .node(name: "apple", title: "Apple"), .hot])
        XCTAssertEqual(HomeTabsStore(preferences: defaults).items.map(\.id), ["following", "node:apple", "hot"])
        XCTAssertNil(defaults.stringArray(forKey: "followedNodes"))
    }

    func testLimitIncludesBuiltinsAndInvalidDuplicatesAreRemoved() -> Void {
        let values: [HomeFeed] = [.all, .all, .node(name: "../bad", title: "Bad"), .node(name: "blank", title: " ")] + (0..<20).map { .node(name: "node\($0)", title: "Node \($0)") }
        let clean = HomeTabsStore.normalize(values)
        XCTAssertEqual(clean.count, 15)
        XCTAssertEqual(clean.first, .all)
        XCTAssertEqual(clean.last?.id, "node:node13")
        XCTAssertEqual(HomeTabsStore.normalize([]), [.all])
    }

    func testDisabledHNLeavesUsableSelection() -> Void {
        let key = "HomeTabsTests.\(UUID())"
        let defaults = UserDefaults(suiteName: key)!
        defer { defaults.removePersistentDomain(forName: key) }
        let store = HomeTabsStore(preferences: defaults)
        store.save([.hackerNews])
        XCTAssertEqual(store.visible(hackerNews: false), [.all])
        XCTAssertEqual(store.visible(hackerNews: true), [.hackerNews])
    }

    func testCorruptPreferencesAndRestoreDefaults() -> Void {
        let key = "HomeTabsTests.\(UUID())"
        let defaults = UserDefaults(suiteName: key)!
        defer { defaults.removePersistentDomain(forName: key) }
        defaults.set(Data("invalid".utf8), forKey: "homeTabs.v1")
        let store = HomeTabsStore(preferences: defaults)
        XCTAssertEqual(store.items.first, .all)
        store.save(HomeTabsStore.defaults(nodes: ["qna"], hackerNews: true))
        XCTAssertEqual(store.items.last, .hackerNews)
        XCTAssertTrue(store.items.contains { $0.id == "node:qna" })
    }
}
