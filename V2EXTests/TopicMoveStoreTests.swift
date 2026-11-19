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

    func testDiagnosticExposesSafeStageAndReason() async {
        let issue = MovePermissionIssue(stage: .form, reason: .http(403))
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in throw issue })
        await store.checkPermission(123, session: account)
        XCTAssertEqual(store.diagnostic(123, session: account)?.state, .failed(issue))
        XCTAssertEqual(issue.code, "FORM_HTTP_403")
        XCTAssertFalse(store.allowed(123, session: account))
        XCTAssertTrue(store.permissions.isEmpty)
    }

    func testManualRecheckRecoversAndUpdatesSharedPermission() async {
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            if count == 1 { throw MovePermissionIssue(stage: .topic, reason: .network) }
            return MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true)
        })
        await store.checkPermission(123, session: account)
        XCTAssertFalse(store.allowed(123, session: account))
        await store.checkPermission(123, session: account)
        XCTAssertEqual(count, 2)
        XCTAssertTrue(store.allowed(123, session: account))
        XCTAssertEqual(store.diagnostic(123, session: account)?.state, .allowed)
    }

    func testManualCheckSharesExistingWatch() async {
        var pending: CheckedContinuation<MoveTopic, Error>?
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            return try await withCheckedThrowingContinuation { pending = $0 }
        })
        let watch = Task { await store.watch(123, session: account) }
        defer { watch.cancel() }
        await settle { pending != nil }
        let manual = Task { await store.checkPermission(123, session: account) }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(store.diagnostic(123, session: account)?.state, .checking)
        pending?.resume(returning: MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true))
        await manual.value
        XCTAssertEqual(count, 1)
        XCTAssertTrue(store.allowed(123, session: account))
    }

    func testSessionChangeClearsDiagnosticsAndOldFailure() async {
        var pending: CheckedContinuation<MoveTopic, Error>?
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            try await withCheckedThrowingContinuation { pending = $0 }
        })
        let manual = Task { await store.checkPermission(123, session: account) }
        await settle { pending != nil }
        account.clear()
        store.synchronize(session: account)
        pending?.resume(throwing: MovePermissionIssue(stage: .form, reason: .http(403)))
        await manual.value
        XCTAssertNil(store.diagnostic(123, session: account))
        XCTAssertTrue(store.diagnostics.isEmpty)
        XCTAssertFalse(store.allowed(123, session: account))
    }

    func testInvalidManualInputAndLoggedOutSessionNeverProbe() async {
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            throw MoveFailure.unavailable
        })
        await store.checkPermission(0, session: account)
        await store.checkPermission(-1, session: account)
        account.clear()
        await store.checkPermission(123, session: account)
        XCTAssertEqual(count, 0)
        XCTAssertTrue(store.diagnostics.isEmpty)
    }

    func testCustomShortcutsAreUnboundedOrderedAndPersistent() -> Void {
        let defaults = isolatedDefaults()
        let store = TopicMoveStore(defaults: defaults)
        let nodes = (0..<80).map { MoveNode(name: "node\($0)", title: "节点\($0)") }
        XCTAssertEqual(store.shortcuts(defaults: nodes, directory: []), nodes)
        let custom = Array(nodes.reversed())
        store.saveShortcuts(custom + [custom[0], MoveNode(name: "bad/path", title: "Invalid")])
        let restored = TopicMoveStore(defaults: defaults)
        XCTAssertEqual(restored.shortcuts(defaults: [], directory: []), custom)
        restored.saveShortcuts(Array(custom.dropFirst()))
        XCTAssertEqual(restored.customShortcuts?.count, 79)
        XCTAssertEqual(TopicMoveStore(defaults: defaults).customShortcuts?.first, custom[1])
    }

    func testEmptyShortcutsAndRestoreDefaultAreDifferent() -> Void {
        let defaults = isolatedDefaults()
        let store = TopicMoveStore(defaults: defaults)
        let website = [MoveNode(name: "qna", title: "问与答")]
        store.saveShortcuts([])
        XCTAssertEqual(TopicMoveStore(defaults: defaults).shortcuts(defaults: website, directory: []), [])
        store.saveShortcuts(nil)
        XCTAssertEqual(TopicMoveStore(defaults: defaults).shortcuts(defaults: website, directory: []), website)
    }

    func testShortcutsUpdateTitlesWithoutDroppingMissingNodes() -> Void {
        let store = TopicMoveStore(defaults: isolatedDefaults())
        store.saveShortcuts([MoveNode(name: "qna", title: "Old"), MoveNode(name: "missing", title: "Saved")])
        let current = MoveNode(name: "qna", title: "问与答", aliases: ["qa"])
        XCTAssertEqual(store.shortcuts(defaults: [], directory: [current]), [current, MoveNode(name: "missing", title: "Saved")])
    }

    func testCorruptOrOtherOriginShortcutsDoNotOverrideDefaults() -> Void {
        let defaults = isolatedDefaults()
        defaults.set(Data("invalid".utf8), forKey: "topicMove.shortcuts.\(V2EXEndpoint.base)")
        defaults.set(try? JSONEncoder().encode([MoveNode(name: "private", title: "Other")]), forKey: "topicMove.shortcuts.https://other.invalid")
        let store = TopicMoveStore(defaults: defaults)
        let website = [MoveNode(name: "qna", title: "问与答")]
        XCTAssertNil(store.customShortcuts)
        XCTAssertEqual(store.shortcuts(defaults: website, directory: []), website)
    }

    func testSubmittedNodeIsTransientAndExpires() -> Void {
        var clock = Date()
        let defaults = isolatedDefaults()
        let store = TopicMoveStore(defaults: defaults, now: { clock })
        let node = MoveNode(name: "sandbox", title: "沙盒")
        store.submitted(.submitted(node), topicID: 123)
        XCTAssertEqual(store.movedNodes[123], node)
        XCTAssertNil(TopicMoveStore(defaults: defaults).movedNodes[123])
        clock = clock.addingTimeInterval(301)
        store.expireNodes()
        XCTAssertNil(store.movedNodes[123])
    }

    func testConfirmedNodeCacheExpiresAfterRestart() -> Void {
        var clock = Date()
        let defaults = isolatedDefaults()
        let store = TopicMoveStore(defaults: defaults, now: { clock })
        store.record(MoveNode(name: "sandbox", title: "沙盒"), topicID: 123)
        clock = clock.addingTimeInterval(301)
        XCTAssertTrue(TopicMoveStore(defaults: defaults, now: { clock }).movedNodes.isEmpty)
    }

    func testDetailProbeCompletesAndReusesValidPermission() async -> Void {
        var count = 0
        let account = session()
        let store = TopicMoveStore(defaults: isolatedDefaults(), fetchPermission: { _, _, _ in
            count += 1
            return MoveTopic(node: MoveNode(name: "qna", title: "问与答"), canMove: true)
        })
        await store.watch(123, session: account)
        await store.watch(123, session: account)
        XCTAssertEqual(count, 1)
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
