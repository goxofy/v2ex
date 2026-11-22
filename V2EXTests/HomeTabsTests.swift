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

    private func sample(_ offset: CGFloat, inset: CGFloat = 0, height: CGFloat = 600) -> HomeNodeScrollSample {
        HomeNodeScrollSample(offset: offset, topInset: inset, viewportHeight: height)
    }

    func testProgressTracksEveryPixelWithoutAThreshold() -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        for offset in 1...160 {
            state.receive(sample(CGFloat(offset)))
            XCTAssertEqual(state.progress, CGFloat(offset) / 160, accuracy: 0.000001)
        }
        for offset in (0..<160).reversed() {
            state.receive(sample(CGFloat(offset)))
            XCTAssertEqual(state.progress, CGFloat(offset) / 160, accuracy: 0.000001)
        }
    }

    func testReverseScrollRevealsHeaderFarDownTheFeed() -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(2000))
        XCTAssertEqual(state.progress, 1)
        state.receive(sample(1960))
        XCTAssertEqual(state.progress, 0.75)
        state.receive(sample(1840))
        XCTAssertEqual(state.progress, 0)
        state.receive(sample(1856))
        XCTAssertEqual(state.progress, 0.1, accuracy: 0.000001)
    }

    func testTopAndPullOverscrollFullyExpandWithoutPrematureCollapse() -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(160))
        state.receive(sample(-60))
        XCTAssertEqual(state.progress, 0)
        state.receive(sample(-20))
        XCTAssertEqual(state.progress, 0)
        state.receive(sample(8))
        XCTAssertEqual(state.progress, 0.05, accuracy: 0.000001)
        state.receive(sample(0))
        XCTAssertEqual(state.progress, 0)
    }

    func testResizeRebasesWithoutDrivingProgressAndInvalidSamplesAreIgnored() -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(80))
        state.receive(sample(300, inset: 20, height: 500))
        XCTAssertEqual(state.progress, 0.5)
        for bad: CGFloat in [.nan, .infinity, -.infinity] {
            state.receive(sample(bad))
            state.receive(sample(999, inset: bad))
            state.receive(sample(999, height: bad))
        }
        state.receive(sample(999, height: 0))
        XCTAssertEqual(state.progress, 0.5)
        state.receive(sample(316, inset: 20, height: 500))
        XCTAssertEqual(state.progress, 0.6, accuracy: 0.000001)
    }

    func testNoProgressContinuesWhileFingerHoldsStill() async throws -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(64))
        try await Task.sleep(for: .milliseconds(180))
        XCTAssertEqual(state.progress, 0.4, accuracy: 0.000001)
        state.receive(sample(64))
        XCTAssertEqual(state.progress, 0.4, accuracy: 0.000001)
    }

    func testIdleSnapUsesNearestEndpointAndReduceMotion() -> Void {
        for (offset, expected): (CGFloat, CGFloat) in [(79, 0), (80, 1), (120, 1)] {
            let state = HomeNodeScrollState()
            state.receive(sample(0))
            state.receive(sample(offset))
            XCTAssertEqual(state.snapTarget, expected)
            state.settle(reduceMotion: true)
            XCTAssertEqual(state.progress, expected)
            // A snap does not change the offset origin: the next pixel remains one pixel.
            state.receive(sample(offset + (expected == 0 ? 1 : -1)))
            XCTAssertEqual(state.progress, expected == 0 ? 1 / 160 : 159 / 160, accuracy: 0.000001)
        }
    }

    func testIdleSnapCompletesAndCanBeInterruptedAtItsCurrentProgress() async throws -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(64))
        state.settle(reduceMotion: false)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertLessThan(state.progress, 0.4)
        XCTAssertGreaterThan(state.progress, 0)
        state.interrupt()
        let interrupted = state.progress
        try await Task.sleep(for: .milliseconds(160))
        XCTAssertEqual(state.progress, interrupted)
        state.receive(sample(80))
        XCTAssertEqual(state.progress, interrupted + 0.1, accuracy: 0.000001)
        state.settle(reduceMotion: false)
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(state.progress, 0)
    }

    func testReturningToTopCancelsSnapAndCannotRecollapseLater() async throws -> Void {
        let state = HomeNodeScrollState()
        state.receive(sample(0))
        state.receive(sample(120))
        state.settle(reduceMotion: false)
        state.receive(sample(0))
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(state.progress, 0)
    }

    func testFeedsKeepIndependentContinuousStateAndSnapLifecycle() async throws -> Void {
        let states = HomeNodeScrollStates()
        let first = states.state(for: .all)
        let second = states.state(for: .r2)
        first.receive(sample(0))
        first.receive(sample(40))
        second.receive(sample(0))
        second.receive(sample(120))
        XCTAssertTrue(states.state(for: .all) === first)
        XCTAssertEqual(first.progress, 0.25)
        XCTAssertEqual(second.progress, 0.75)
        first.settle(reduceMotion: false)
        second.settle(reduceMotion: false)
        states.interruptAll()
        try await Task.sleep(for: .milliseconds(180))
        XCTAssertEqual(first.progress, 0.25)
        XCTAssertEqual(second.progress, 0.75)
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
