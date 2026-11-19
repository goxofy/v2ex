import Foundation
import Combine

@MainActor
final class HomeViewModel: ObservableObject {
    typealias Feed = HomeFeed

    @Published var feed: Feed = .all
    @Published private(set) var topics: [V2Topic] = []
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var moreError: String?
    @Published private(set) var hasMore = false
    @Published private(set) var paginationID = UUID()

    private struct Snapshot {
        var topics: [V2Topic]
        var page: Int
        var hasMore: Bool
    }
    private var cache: [Feed: Snapshot] = [:]
    private var generation = UUID()
    private var sessionIdentity = ""
    private weak var webSession: V2EXSessionStore?
    private var cookie = ""
    private var base = V2EXEndpoint.base
    private let fetchFollowing: (URL, Int, String) async throws -> FollowingPage

    init(fetchFollowing: @escaping (URL, Int, String) async throws -> FollowingPage = { base, page, cookie in
        try await FollowingService(base: base).topics(page: page, cookie: cookie)
    }) {
        self.fetchFollowing = fetchFollowing
    }

    func synchronize(session: V2EXSessionStore) -> Void {
        webSession = session
        let identity = "\(session.revision)|\(V2EXEndpoint.base)"
        guard identity != sessionIdentity else { return }
        sessionIdentity = identity
        cookie = session.cookie
        base = V2EXEndpoint.base
        cache[.following] = nil
        if feed == .following {
            generation = UUID()
            topics = []
            hasMore = false
            isLoading = false
            errorMessage = nil
            moreError = nil
            paginationID = UUID()
        }
    }

    private var sessionCurrent: Bool {
        guard let webSession else { return false }
        return sessionIdentity == "\(webSession.revision)|\(V2EXEndpoint.base)"
    }

    func load(feed: Feed, session: V2EXSessionStore, force: Bool = false) async {
        synchronize(session: session)
        self.feed = feed
        let request = UUID()
        generation = request
        moreError = nil
        errorMessage = nil
        if !force, let cached = cache[feed] {
            topics = cached.topics
            hasMore = cached.hasMore
            isLoading = false
            return
        }
        if !force { topics = [] }
        isLoading = true
        defer { if generation == request { isLoading = false } }
        do {
            let snapshot = try await fetch(feed: feed, page: 1)
            guard !Task.isCancelled, generation == request, self.feed == feed, feed != .following || sessionCurrent else { return }
            cache[feed] = snapshot
            topics = snapshot.topics
            hasMore = snapshot.hasMore
            paginationID = UUID()
        } catch {
            guard !Task.isCancelled, generation == request, self.feed == feed, feed != .following || sessionCurrent else { return }
            errorMessage = error.localizedDescription
            topics = cache[feed]?.topics ?? []
            hasMore = cache[feed]?.hasMore ?? false
        }
    }

    func loadMore(feed: Feed) async {
        guard self.feed == feed, !isLoading, hasMore, let previous = cache[feed] else { return }
        let request = generation
        isLoading = true
        moreError = nil
        defer { if generation == request { isLoading = false } }
        do {
            var next = try await fetch(feed: feed, page: previous.page + 1)
            guard !Task.isCancelled, generation == request, self.feed == feed, feed != .following || sessionCurrent else { return }
            var seen = Set(previous.topics.map(\.id))
            next.topics = previous.topics + next.topics.filter { seen.insert($0.id).inserted }
            cache[feed] = next
            topics = next.topics
            hasMore = next.hasMore
            paginationID = UUID()
        } catch {
            // The footer re-runs its task when it scrolls back into view.
            guard generation == request, self.feed == feed, !error.isCancellation, feed != .following || sessionCurrent else { return }
            moreError = error.localizedDescription
        }
    }

    private func fetch(feed: Feed, page: Int) async throws -> Snapshot {
        switch feed {
        case .hot:
            return Snapshot(topics: try await V2EXClient.shared.hotTopics(), page: 1, hasMore: false)
        case .r2:
            return Snapshot(topics: try await V2EXClient.shared.r2Topics(), page: 1, hasMore: false)
        case .hackerNews:
            return Snapshot(topics: [], page: 1, hasMore: false)
        case .following:
            guard let url = URL(string: base) else { throw V2EXError.decoding("站点地址无效") }
            guard !cookie.isEmpty else { throw V2EXError.sessionExpired }
            let result = try await fetchFollowing(url, page, cookie)
            return Snapshot(topics: result.topics, page: page, hasMore: result.hasMore)
        case .all, .node:
            let node: String?
            if case .node(let name, _) = feed { node = name } else { node = nil }
            let result = try await V2EXClient.shared.publicTopicPage(node: node, page: page)
            return Snapshot(topics: result.topics, page: page, hasMore: result.hasMore)
        }
    }

    /// 明显的推广/广告信号词。命中则从首页 feed 隐藏——V2EX 的最新流里
    /// 偶尔会混入“免费送/邀请码/住宅 IP”这类推广帖，它们不该占首页版面。
    private static let promotionSignals = [
        "邀请码", "免费送", "动态住宅", "住宅 ip", "住宅ip", "流量用不完", "注册送", "返利",
    ]

    static func isPromotion(_ topic: V2Topic) -> Bool {
        let haystack = "\(topic.title) \(topic.authorName)".lowercased()
        return promotionSignals.contains { haystack.contains($0) }
    }
}
