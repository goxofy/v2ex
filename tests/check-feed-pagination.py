#!/usr/bin/env python3
"""Compile the production feed model against a deterministic paged transport."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
model = (root / 'V2EX/Features/Home/HomeViewModel.swift').read_text()
stubs = r'''
enum HomeFeed: Hashable { case all, hot, r2, hackerNews, following, node(String, String) }
struct V2Topic { let id: Int; var title = "topic"; var authorName = "author" }
enum V2EXEndpoint { static let base = "https://www.v2ex.com" }
enum V2EXError: Error { case decoding(String), sessionExpired }
extension Error { var isCancellation: Bool { self is CancellationError } }
@MainActor final class V2EXSessionStore { var revision = 0; var cookie = "fixture" }
struct FollowingPage { let topics: [V2Topic]; let hasMore: Bool }
struct FollowingService {
    let base: URL
    func topics(page: Int, cookie: String) async throws -> FollowingPage {
        .init(topics: [V2Topic(id: page)], hasMore: page == 1)
    }
}
final class V2EXClient {
    static let shared = V2EXClient()
    struct TopicPage { let topics: [V2Topic]; let hasMore: Bool }
    var calls: [String] = []
    var fail = false
    var pause = false
    var continuation: CheckedContinuation<TopicPage, Error>?
    func publicTopicPage(node: String? = nil, page: Int) async throws -> TopicPage {
        calls.append("\(node ?? "recent"):\(page)")
        if pause { return try await withCheckedThrowingContinuation { continuation = $0 } }
        if fail { throw NSError(domain: "offline", code: 1) }
        let ids = page == 1 ? [1, 2] : page == 2 ? [2, 3] : [4]
        return TopicPage(topics: ids.map { V2Topic(id: $0) }, hasMore: page < 3)
    }
    func hotTopics() async throws -> [V2Topic] { [V2Topic(id: 10)] }
    func r2Topics() async throws -> [V2Topic] { [V2Topic(id: 20)] }
}
@main struct Checks {
    @MainActor static func main() async -> Void {
        let client = V2EXClient.shared
        let session = V2EXSessionStore()
        let model = HomeViewModel(client: client)
        await model.load(feed: .all, session: session)
        assert(model.topics.map(\.id) == [1,2] && model.hasMore)
        client.fail = true
        await model.loadMore(feed: .all)
        assert(model.topics.map(\.id) == [1,2] && model.hasMore && model.moreError != nil)
        client.fail = false
        await model.loadMore(feed: .all)
        assert(client.calls.suffix(2) == ["recent:2", "recent:2"])
        assert(model.topics.map(\.id) == [1,2,3] && model.moreError == nil)
        await model.loadMore(feed: .all)
        assert(model.topics.map(\.id) == [1,2,3,4] && !model.hasMore)
        await model.load(feed: .hot, session: session)
        assert(!model.hasMore)
        await model.load(feed: .all, session: session)
        assert(model.topics.count == 4 && !model.hasMore)
        await model.load(feed: .all, session: session, force: true)
        assert(model.topics.count == 2 && model.hasMore)
        client.pause = true
        let pending = Task { await model.loadMore(feed: .all) }
        while client.continuation == nil { await Task.yield() }
        await model.load(feed: .r2, session: session)
        client.continuation?.resume(returning: .init(topics: [V2Topic(id: 99)], hasMore: true))
        await pending.value
        assert(model.feed == .r2 && model.topics.map(\.id) == [20] && !model.isLoading)
        client.pause = false
        await model.load(feed: .following, session: session)
        await model.loadMore(feed: .following)
        assert(model.topics.map(\.id) == [1,2] && !model.hasMore)
        session.revision += 1
        session.cookie = ""
        await model.load(feed: .following, session: session)
        assert(model.topics.isEmpty && model.errorMessage != nil)
        print("PASS: pagination, retry cursor, deduplication, end, cache, refresh, stale response, following session")
    }
}
'''
job_dir = os.environ.get('CLAUDE_JOB_DIR')
scratch = str(Path(job_dir) / 'tmp') if job_dir else None
with tempfile.TemporaryDirectory(prefix='v2ex-pagination-', dir=scratch) as tmp:
    source = Path(tmp) / 'checks.swift'
    source.write_text(model + stubs)
    binary = Path(tmp) / 'checks'
    subprocess.run(['swiftc', '-parse-as-library', '-module-cache-path', str(Path(tmp) / 'module-cache'), str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
