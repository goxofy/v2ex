import XCTest
@testable import V2EX

@MainActor
final class TopicMoveStoreTests: XCTestCase {
    private func session() -> V2EXSessionStore {
        let session = V2EXSessionStore(inMemory: true)
        session.save(cookie: "session=fake", username: "fixture")
        return session
    }

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "TopicMoveTests.\(UUID().uuidString)")!
    }

    private func settle(_ predicate: () -> Bool) async {
        for _ in 0..<1000 {
            if predicate() { return }
            await Task.yield()
        }
        XCTFail("State did not settle")
    }

    func testUnknownNeverShowsMove() {
        let store = TopicMoveStore(defaults: isolatedDefaults())
        XCTAssertFalse(store.allowed(123, session: session()))
    }

    func testPermissionIsTopicScopedAndExpires() async {
        var clock = Date()
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), now: { clock }, fetchPermission: { _, _, _ in
            MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true)
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { store.allowed(123, session: account) }
        XCTAssertFalse(store.allowed(456, session: account))
        clock = clock.addingTimeInterval(61)
        XCTAssertFalse(store.allowed(123, session: account))
    }

    func testLogoutImmediatelyInvalidatesCachedPermission() async {
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true)
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { store.allowed(123, session: account) }
        account.clear()
        XCTAssertFalse(store.allowed(123, session: account))
        store.synchronize(session: account)
        XCTAssertTrue(store.permissions.isEmpty)
    }

    func testOldAccountResponseCannotRestorePermission() async {
        var pending: CheckedContinuation<MoveTopic, Error>?
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            try await withCheckedThrowingContinuation { pending = $0 }
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { pending != nil }
        account.save(cookie: "session=other", username: "ordinary")
        store.synchronize(session: account)
        pending?.resume(returning: MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(store.permissions.isEmpty)
        XCTAssertFalse(store.allowed(123, session: account))
    }

    func testDuplicateConsumersShareProbe() async {
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            return MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true)
        })
        let first = Task { await store.watch(123, session: account) }
        let second = Task { await store.watch(123, session: account) }
        defer { first.cancel(); second.cancel() }
        await settle { store.allowed(123, session: account) }
        XCTAssertEqual(count, 1)
    }

    func testFailureStaysUnknownRatherThanDenied() async {
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            throw MoveFailure.unavailable
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { count == 1 }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(store.permissions.isEmpty)
        XCTAssertFalse(store.allowed(123, session: account))
    }

    func testDeniedResponseIsNotPermission() async {
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: false)
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { store.permissions[123] != nil }
        XCTAssertFalse(store.allowed(123, session: account))
    }

    func testInvalidationRejectsEarlierProbe() async {
        var pending: CheckedContinuation<MoveTopic, Error>?
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            try await withCheckedThrowingContinuation { pending = $0 }
        })
        let task = Task { await store.watch(123, session: account) }
        defer { task.cancel() }
        await settle { pending != nil }
        store.invalidate(123)
        pending?.resume(returning: MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(store.permissions.isEmpty)
    }

    func testProbesHaveAtMostTwoConcurrentRequests() async throws {
        var pending: [CheckedContinuation<MoveTopic, Error>] = []
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            try await withCheckedThrowingContinuation { pending.append($0) }
        })
        let tasks = (123...125).map { id in Task { await store.watch(id, session: account) } }
        await settle { pending.count == 2 }
        try await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertEqual(pending.count, 2)
        tasks.forEach { $0.cancel() }
        for task in tasks { await task.value }
        pending.forEach { $0.resume(throwing: CancellationError()) }
    }

    func testOnlyConfirmedNodeMetadataPersists() {
        let defaults = isolatedDefaults()
        let store = TopicMoveStore(defaults: defaults)
        let node = MoveNode(name: "sandbox", title: "沙盒")
        store.record(node, topicID: 123)
        let restored = TopicMoveStore(defaults: defaults)
        XCTAssertEqual(restored.movedNodes[123], node)
        XCTAssertTrue(restored.permissions.isEmpty)
    }
}
