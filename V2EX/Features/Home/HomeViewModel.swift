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

    enum RefreshSource: String { case automatic, pull, button, retry }

    struct RefreshState: Equatable {
        enum Phase: Equatable { case idle, refreshing, success(unchanged: Bool), failed, cancelled }
        var phase: Phase = .idle
        var lastSuccess: Date?
    }

    @Published private(set) var refreshStates: [Feed: RefreshState] = [:]

    private struct ActiveRequest {
        let id: UUID
        let feed: Feed
        let isFirstPage: Bool
    }
    private var activeRequest: ActiveRequest?

    func refreshState(for feed: Feed) -> RefreshState {
        refreshStates[feed] ?? RefreshState()
    }

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
    private let client: V2EXClient
    private let fetchFollowing: (URL, Int, String) async throws -> FollowingPage

    init(client: V2EXClient = .shared, fetchFollowing: @escaping (URL, Int, String) async throws -> FollowingPage = { base, page, cookie in
        try await FollowingService(base: base).topics(page: page, cookie: cookie)
    }) {
        self.client = client
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
        if activeRequest?.feed == .following { invalidateActiveRequest() }
        refreshStates[.following] = nil
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

    func refresh(feed: Feed, session: V2EXSessionStore, source: RefreshSource) async -> Void {
        // Read selection when the callback runs, not when TabView creates its neighboring pages.
        guard self.feed == feed, feed != .hackerNews else {
            FeedRequestTrace.record("ignored source=\(source.rawValue) reason=inactive-page", request: UUID())
            return
        }
        await load(feed: feed, session: session, force: true, source: source)
    }

    func load(feed: Feed, session: V2EXSessionStore, force: Bool = false, source: RefreshSource = .automatic) async -> Void {
        synchronize(session: session)
        if let active = activeRequest, active.feed == feed, self.feed == feed,
           !force || active.isFirstPage {
            // Ordinary on-appear/cache loads must not invalidate an in-flight refresh.
            FeedRequestTrace.record("coalesced source=\(source.rawValue)", request: active.id)
            return
        }
        invalidateActiveRequest()
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
        activeRequest = ActiveRequest(id: request, feed: feed, isFirstPage: true)
        refreshStates[feed, default: RefreshState()].phase = .refreshing
        FeedRequestTrace.record("start source=\(source.rawValue)", request: request)
        defer { finishRequest(request) }
        do {
            let snapshot = try await FeedRequestTrace.$id.withValue(request) {
                try await fetch(feed: feed, page: 1)
            }
            FeedRequestTrace.record("parsed rows=\(snapshot.topics.count)", request: request)
            guard canCommit(request, generation: request, feed: feed) else { return }
            let unchanged = cache[feed].map { $0.topics == snapshot.topics } ?? false
            cache[feed] = snapshot
            topics = snapshot.topics
            hasMore = snapshot.hasMore
            paginationID = UUID()
            refreshStates[feed] = RefreshState(phase: .success(unchanged: unchanged), lastSuccess: Date())
            FeedRequestTrace.record("committed unchanged=\(unchanged)", request: request)
        } catch {
            guard canCommit(request, generation: request, feed: feed) else { return }
            if error.isCancellation {
                refreshStates[feed, default: RefreshState()].phase = .cancelled
                FeedRequestTrace.record("cancelled", request: request)
            } else {
                errorMessage = error.localizedDescription
                refreshStates[feed, default: RefreshState()].phase = .failed
                FeedRequestTrace.record("failed", request: request)
            }
            topics = cache[feed]?.topics ?? []
            hasMore = cache[feed]?.hasMore ?? false
        }
    }

    func loadMore(feed: Feed) async -> Void {
        guard self.feed == feed, !isLoading, hasMore, let previous = cache[feed] else { return }
        let request = UUID()
        let currentGeneration = generation
        activeRequest = ActiveRequest(id: request, feed: feed, isFirstPage: false)
        isLoading = true
        moreError = nil
        defer { finishRequest(request) }
        do {
            var next = try await fetch(feed: feed, page: previous.page + 1)
            guard canCommit(request, generation: currentGeneration, feed: feed) else { return }
            var seen = Set(previous.topics.map(\.id))
            next.topics = previous.topics + next.topics.filter { seen.insert($0.id).inserted }
            cache[feed] = next
            topics = next.topics
            hasMore = next.hasMore
            paginationID = UUID()
        } catch {
            // The footer re-runs its task when it scrolls back into view.
            guard canCommit(request, generation: currentGeneration, feed: feed), !error.isCancellation else { return }
            moreError = error.localizedDescription
        }
    }

    private func canCommit(_ request: UUID, generation: UUID, feed: Feed) -> Bool {
        let reason: String?
        if activeRequest?.id != request || self.generation != generation { reason = "superseded" }
        else if self.feed != feed { reason = "feed-changed" }
        else if feed == .following && !sessionCurrent { reason = "session-changed" }
        else if Task.isCancelled { reason = "cancelled" }
        else { reason = nil }
        if let reason {
            FeedRequestTrace.record("discarded reason=\(reason)", request: request)
            return false
        }
        return true
    }

    private func finishRequest(_ request: UUID) -> Void {
        guard let active = activeRequest, active.id == request else { return }
        if active.isFirstPage, refreshStates[active.feed]?.phase == .refreshing {
            refreshStates[active.feed, default: RefreshState()].phase = .cancelled
        }
        activeRequest = nil
        isLoading = false
    }

    private func invalidateActiveRequest() -> Void {
        guard let active = activeRequest else { return }
        FeedRequestTrace.record("superseded", request: active.id)
        finishRequest(active.id)
    }

    private func fetch(feed: Feed, page: Int) async throws -> Snapshot {
        switch feed {
        case .hot:
            return Snapshot(topics: try await client.hotTopics(), page: 1, hasMore: false)
        case .r2:
            return Snapshot(topics: try await client.r2Topics(), page: 1, hasMore: false)
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
            let result = try await client.publicTopicPage(node: node, page: page)
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
