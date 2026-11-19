import XCTest
@testable import V2EX

@MainActor
final class HomeTabsTests: XCTestCase {
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
