import XCTest
@testable import V2EX

final class FollowingTests: XCTestCase {
    private let base = URL(string: "https://www.v2ex.com")!

    static func row(_ id: Int, title: String = "Test topic") -> String {
        """
        <div class="cell item"><table><tr><td><img class="avatar" src="/avatar.png"></td><td>
        <span class="item_title"><a href="/t/\(id)#reply2">\(title)</a></span>
        <span><a class="node" href="/go/apple">Apple</a> · <strong><a href="/member/testuser">testuser</a></strong></span>
        <a class="count_livid" href="/t/\(id)">2</a>
        </td></tr></table></div>
        """
    }

    static func page(_ rows: String, next: Int? = nil) -> String {
        let pagination = next.map { "<a href='/my/following?p=\($0)'>Next</a>" } ?? ""
        return "<div id='Main'><div class='box'><a href='/my/following'>特别关注</a>\(rows)\(pagination)</div></div>"
    }

    func testRefreshFeedbackIncludesSuccessfulFetchTime() throws -> Void {
        let date = Date(timeIntervalSince1970: 100)
        let feedback = try XCTUnwrap(HomeRefreshFeedback(
            feed: .r2, previous: .init(phase: .refreshing),
            current: .init(phase: .success(unchanged: false), lastSuccess: date)
        ))
        XCTAssertEqual(feedback.feed, .r2)
        XCTAssertEqual(feedback.title, "已获取列表")
        XCTAssertEqual(feedback.lastSuccess, date)
    }

    func testUnchangedRefreshStillProducesNewFeedback() throws -> Void {
        let old = Date(timeIntervalSince1970: 100)
        let current = HomeViewModel.RefreshState(phase: .success(unchanged: true), lastSuccess: old.addingTimeInterval(1))
        let feedback = try XCTUnwrap(HomeRefreshFeedback(
            feed: .r2, previous: .init(phase: .success(unchanged: true), lastSuccess: old), current: current
        ))
        XCTAssertEqual(feedback.title, "已获取，列表无变化")
        XCTAssertEqual(feedback.lastSuccess, current.lastSuccess)
    }

    func testCachedRefreshStateDoesNotReplayFeedback() -> Void {
        let state = HomeViewModel.RefreshState(phase: .success(unchanged: false), lastSuccess: Date())
        XCTAssertNil(HomeRefreshFeedback(feed: .all, previous: state, current: state))
        XCTAssertNil(HomeRefreshFeedback(feed: .all, previous: state, current: .init(phase: .success(unchanged: false))))
        XCTAssertNil(HomeRefreshFeedback(feed: .all, previous: nil, current: .init(phase: .success(unchanged: false))))
    }

    func testIdleAndLoadingDoNotProduceFeedback() -> Void {
        for phase: HomeViewModel.RefreshState.Phase in [.idle, .refreshing] {
            XCTAssertNil(HomeRefreshFeedback(feed: .all, previous: nil, current: .init(phase: phase)))
            XCTAssertNil(HomeRefreshFeedback.retryMessage(error: nil, phase: phase))
        }
    }

    func testFailureFeedbackPreservesPreviousSuccessTime() throws -> Void {
        let date = Date(timeIntervalSince1970: 100)
        let failed = HomeViewModel.RefreshState(phase: .failed, lastSuccess: date)
        let feedback = try XCTUnwrap(HomeRefreshFeedback(
            feed: .following, previous: .init(phase: .refreshing, lastSuccess: date), current: failed
        ))
        XCTAssertEqual(feedback.title, "刷新失败，可在列表中重试")
        XCTAssertEqual(feedback.lastSuccess, date)
        XCTAssertNil(HomeRefreshFeedback(feed: .following, previous: failed, current: failed))
        XCTAssertEqual(HomeRefreshFeedback.retryMessage(error: "Network error", phase: .failed), "Network error")
    }

    func testCancellationKeepsRetryReachableWithoutError() throws -> Void {
        let cancelled = HomeViewModel.RefreshState(phase: .cancelled)
        let feedback = try XCTUnwrap(HomeRefreshFeedback(
            feed: .r2, previous: .init(phase: .refreshing), current: cancelled
        ))
        XCTAssertEqual(feedback.title, "刷新已取消，可在列表中重试")
        XCTAssertNil(feedback.lastSuccess)
        XCTAssertNil(HomeRefreshFeedback(feed: .r2, previous: cancelled, current: cancelled))
        XCTAssertEqual(HomeRefreshFeedback.retryMessage(error: nil, phase: .cancelled), "刷新已取消，请重试")
        XCTAssertEqual(HomeRefreshFeedback.retryMessage(error: "Specific error", phase: .cancelled), "Specific error")
    }

    func testFailedStateKeepsRetryReachableAfterErrorIsCleared() -> Void {
        XCTAssertEqual(HomeRefreshFeedback.retryMessage(error: nil, phase: .failed), "刷新失败，请重试")
        XCTAssertEqual(HomeRefreshFeedback.retryMessage(error: " \n", phase: .failed), "刷新失败，请重试")
    }

    func testSuccessClearsRetryFallback() -> Void {
        XCTAssertNil(HomeRefreshFeedback.retryMessage(error: nil, phase: .success(unchanged: true)))
        XCTAssertNil(HomeRefreshFeedback.retryMessage(error: nil, phase: .success(unchanged: false)))
    }

    func testSeparateFeedbackEventsHaveDifferentTimerIdentities() throws -> Void {
        let state = HomeViewModel.RefreshState(phase: .success(unchanged: false), lastSuccess: Date())
        let first = try XCTUnwrap(HomeRefreshFeedback(feed: .all, previous: nil, current: state))
        let second = try XCTUnwrap(HomeRefreshFeedback(feed: .all, previous: nil, current: state))
        XCTAssertNotEqual(first.id, second.id)
    }

    func testDesktopRowsWithoutTopicLinkAndWebsiteOrder() throws -> Void {
        let result = try FollowingPage(html: Self.page(Self.row(9) + Self.row(3) + Self.row(9), next: 2), base: base, page: 1)
        XCTAssertEqual(result.topics.map(\.id), [9, 3])
        XCTAssertEqual(result.topics.first?.member?.username, "testuser")
        XCTAssertEqual(result.topics.first?.node?.name, "apple")
        XCTAssertEqual(result.topics.first?.replies, 2)
        XCTAssertTrue(result.hasMore)
    }

    func testMobileRowsAndUntrustedContentAreSeparated() throws -> Void {
        let html = "<div id='Rightbar'>\(Self.row(99))</div><div id='Wrapper'><div class='content'><div class='box'>\(Self.row(4))<div class='reply_content'>\(Self.row(98))</div><script>\(Self.row(97))</script></div></div></div>"
        XCTAssertEqual(try FollowingPage(html: html, base: base, page: 1).topics.map(\.id), [4])
    }

    func testExplicitEmptyVersusUnknownLoginAndVerification() throws -> Void {
        XCTAssertTrue(try FollowingPage(html: Self.page("关注的人还没有发布话题"), base: base, page: 1).topics.isEmpty)
        for html in ["", Self.page("Unexpected page"), "<input type='password'>", "<form id='challenge-form'></form>", "<div id='Main'>No topics</div>", Self.page("<div class='problem'>Error</div>")] {
            XCTAssertThrowsError(try FollowingPage(html: html, base: base, page: 1))
        }
    }

    func testPaginationRejectsForeignExtraOrWrongPageLinks() throws -> Void {
        for href in ["https://other.invalid/my/following?p=2", "/my/following?p=3", "/my/following?p=2&amp;other=1", "/my/nodes?p=2"] {
            let result = try FollowingPage(html: Self.page(Self.row(1) + "<a href='\(href)'>Next</a>"), base: base, page: 1)
            XCTAssertFalse(result.hasMore, href)
        }
    }

    func testForeignAndMalformedTopicLinksAreNotAccepted() throws -> Void {
        for href in ["https://other.invalid/t/1", "/t/+1", "/t/1?next=x", "/t/1/extra"] {
            let html = Self.page(Self.row(1).replacingOccurrences(of: "/t/1#reply2", with: href))
            XCTAssertThrowsError(try FollowingPage(html: html, base: base, page: 1))
        }
    }

    private func service(_ handler: @escaping (URLRequest) -> (Int, [String: String], String)) -> FollowingService {
        FollowingProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FollowingProtocol.self]
        return FollowingService(base: base, configuration: configuration)
    }

    override func tearDown() -> Void {
        FollowingProtocol.handler = nil
        FollowingProtocol.beforeResponse = nil
        FollowingProtocol.transportError = nil
        super.tearDown()
    }

    func testAuthenticatedPageRequestAndReadOnlyCanonicalRedirect() async throws -> Void {
        var requests: [URLRequest] = []
        let client = service { request in
            requests.append(request)
            if requests.count == 1 { return (302, ["Location": "/my/following"], "") }
            return (200, [:], Self.page(Self.row(5)))
        }
        let result = try await client.topics(page: 1, cookie: "PB3_SESSION=fixture")
        XCTAssertEqual(result.topics.map(\.id), [5])
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests.first?.url?.query, "p=1")
        XCTAssertTrue(requests.allSatisfy { $0.httpMethod == "GET" && $0.value(forHTTPHeaderField: "Cookie") == "PB3_SESSION=fixture" })
    }

    func testUnsafeAndLoginRedirectsNeverReceiveSecondRequest() async -> Void {
        for target in ["https://other.invalid/my/following?p=1", "/signin", "/2fa", "/t/1", "/my/following?p=2"] {
            var count = 0
            let client = service { _ in
                count += 1
                return (302, ["Location": target], "")
            }
            do {
                _ = try await client.topics(page: 1, cookie: "PB3_SESSION=fixture")
                XCTFail("Unexpected success: \(target)")
            } catch { XCTAssertEqual(count, 1) }
        }
    }

    func testInvalidCredentialsDoNotStartRequest() async -> Void {
        var count = 0
        let client = service { _ in
            count += 1
            return (200, [:], Self.page(Self.row(1)))
        }
        for cookie in ["", "session=fixture\r\nInjected: value"] {
            do {
                _ = try await client.topics(page: 1, cookie: cookie)
                XCTFail("Unexpected success")
            } catch { XCTAssertEqual(count, 0) }
        }
    }

    private func publicClient(_ handler: @escaping (URLRequest) -> (Int, [String: String], String)) -> V2EXClient {
        FollowingProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FollowingProtocol.self]
        configuration.timeoutIntervalForRequest = 3
        configuration.timeoutIntervalForResource = 5
        return V2EXClient(webSession: URLSession(configuration: configuration))
    }

    private static func publicPage(_ ids: [Int], next: String? = nil) -> String {
        let rows = ids.map { row($0).replacingOccurrences(of: "<a href=\"/t/", with: "<a class=\"topic-link\" href=\"/t/") }.joined()
        return "<div id=\"Main\">\(rows)\(next.map { "<a href=\"\($0)\">Next</a>" } ?? "")</div>"
    }

    func testPublicFeedsBypassHTTPCacheAndKeepQueryParameters() async throws -> Void {
        var requests: [URLRequest] = []
        let client = publicClient { request in
            requests.append(request)
            return (200, ["Cache-Control": "public, max-age=86400"], Self.publicPage(Array(1...12)))
        }
        _ = try await client.r2Topics()
        _ = try await client.r2Topics()
        _ = try await client.publicTopicPage(page: 1)
        _ = try await client.publicTopicPage(node: "programmer", page: 2)
        _ = try await client.hotTopics()
        XCTAssertEqual(requests.count, 5)
        let urls = try requests.map { try XCTUnwrap(URLComponents(url: XCTUnwrap($0.url), resolvingAgainstBaseURL: false)) }
        XCTAssertEqual(urls.map(\.path), ["/", "/", "/recent", "/go/programmer", "/"])
        XCTAssertEqual(urls[0].queryItems?.first { $0.name == "tab" }?.value, "r2")
        XCTAssertEqual(urls[4].queryItems?.first { $0.name == "tab" }?.value, "hot")
        XCTAssertEqual(urls[2].queryItems?.first { $0.name == "p" }?.value, "1")
        XCTAssertEqual(urls[3].queryItems?.first { $0.name == "p" }?.value, "2")
        let keys = urls.compactMap { $0.queryItems?.first { $0.name == "_" }?.value }
        XCTAssertEqual(Set(keys).count, requests.count)
        for request in requests {
            XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-cache, no-store")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Pragma"), "no-cache")
        }
    }

    func testSameR2ClientReadsChangedRowsWithoutRestart() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage(count == 1 ? [9, 3] : [4, 10]))
        }
        let first = try await client.r2Topics()
        let second = try await client.r2Topics()
        XCTAssertEqual(first.map(\.id), [9, 3])
        XCTAssertEqual(second.map(\.id), [4, 10])
    }

    @MainActor
    func testR2ForceRefreshReplacesSnapshotInSameModelAfterFailure() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            if count == 2 { return (503, [:], "unavailable") }
            return (200, [:], Self.publicPage(count == 1 ? [9, 3] : [4, 10]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        XCTAssertEqual(model.topics.map(\.id), [9, 3])
        await model.load(feed: .r2, session: session)
        XCTAssertEqual(count, 1, "Ordinary tab selection may reuse the snapshot")
        await model.load(feed: .r2, session: session, force: true)
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.topics.map(\.id), [9, 3])
        XCTAssertNotNil(model.errorMessage)
        XCTAssertFalse(model.isLoading)
        await model.load(feed: .r2, session: session, force: true)
        XCTAssertEqual(count, 3)
        XCTAssertEqual(model.topics.map(\.id), [4, 10])
        XCTAssertNil(model.errorMessage)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testAllRefreshResetsPaginationAndKeepsExistingRowsOnFailure() async throws -> Void {
        var requests: [URLRequest] = []
        let client = publicClient { request in
            requests.append(request)
            switch requests.count {
            case 1: return (200, [:], Self.publicPage([9], next: "/recent?p=2"))
            case 2: return (200, [:], Self.publicPage([3]))
            case 3: return (503, [:], "unavailable")
            default: return (200, [:], Self.publicPage([10], next: "/recent?p=2"))
            }
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .all, session: session)
        XCTAssertTrue(model.hasMore)
        await model.loadMore(feed: .all)
        XCTAssertEqual(model.topics.map(\.id), [9, 3])
        XCTAssertFalse(model.hasMore)
        await model.load(feed: .all, session: session, force: true)
        XCTAssertEqual(model.topics.map(\.id), [9, 3])
        XCTAssertNotNil(model.errorMessage)
        await model.load(feed: .all, session: session, force: true)
        XCTAssertEqual(model.topics.map(\.id), [10])
        XCTAssertTrue(model.hasMore)
        XCTAssertNil(model.errorMessage)
        let pages = requests.compactMap { request in
            request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?
                .queryItems?.first { $0.name == "p" }?.value
        }
        XCTAssertEqual(pages, ["1", "2", "1", "1"])
    }

    func testPublicFeedHTTPErrorCannotReturnStaleRowsAsSuccess() async -> Void {
        let client = publicClient { _ in (503, [:], Self.publicPage([9])) }
        do {
            _ = try await client.r2Topics()
            XCTFail("HTTP errors must not be parsed as a successful ranking")
        } catch { }
    }

    @MainActor
    func testOrdinaryR2LoadCannotDiscardInFlightRefresh() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage(count == 1 ? [9] : [10]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let refresh = Task { await model.load(feed: .r2, session: session, force: true) }
        await fulfillment(of: [gate.started], timeout: 3)
        await model.load(feed: .r2, session: session)
        XCTAssertTrue(model.isLoading, "A cache read must not finish the active refresh")
        gate.release()
        await refresh.value
        XCTAssertEqual(model.topics.map(\.id), [10])
        XCTAssertEqual(count, 2)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testDelayedR2RefreshCannotOverwriteSelectedFeed() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { request, deliver in
            if request.url?.path == "/" { gate.hold(deliver) } else { deliver() }
        }
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        let refresh = Task { await model.load(feed: .r2, session: session, force: true) }
        await fulfillment(of: [gate.started], timeout: 3)
        await model.load(feed: .all, session: session)
        gate.release()
        await refresh.value
        XCTAssertEqual(model.feed, .all)
        XCTAssertEqual(model.topics.map(\.id), [3])
        XCTAssertFalse(model.isLoading)
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .cancelled)
        XCTAssertEqual(model.refreshState(for: .all).phase, .success(unchanged: false))
    }

    @MainActor
    func testOrdinaryLoadCannotDiscardInFlightPagination() async throws -> Void {
        let client = publicClient { request in
            let first = request.url?.query?.contains("p=1") == true
            return (200, [:], Self.publicPage(first ? [1] : [2], next: first ? "/recent?p=2" : nil))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .all, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let nextPage = Task { await model.loadMore(feed: .all) }
        await fulfillment(of: [gate.started], timeout: 3)
        await model.load(feed: .all, session: session)
        XCTAssertTrue(model.isLoading)
        gate.release()
        await nextPage.value
        XCTAssertEqual(model.topics.map(\.id), [1, 2])
        XCTAssertFalse(model.hasMore)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testManualRefreshSupersedesPaginationWithoutLateAppend() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count], next: count == 1 ? "/recent?p=2" : nil))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .all, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { request, deliver in
            if request.url?.query?.contains("p=2") == true { gate.hold(deliver) } else { deliver() }
        }
        let nextPage = Task { await model.loadMore(feed: .all) }
        await fulfillment(of: [gate.started], timeout: 3)
        await model.refresh(feed: .all, session: session, source: .pull)
        gate.release()
        await nextPage.value
        XCTAssertEqual(model.topics.map(\.id), [3])
        XCTAssertFalse(model.hasMore)
        XCTAssertFalse(model.isLoading)
        XCTAssertEqual(model.refreshState(for: .all).phase, .success(unchanged: false))
    }

    @MainActor
    func testRefreshCallbackChecksSelectionAtExecutionTime() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        // Create the callback while the R2 page is inactive, as TabView does for neighbors.
        let refreshR2: () async -> Void = {
            await model.refresh(feed: .r2, session: session, source: .pull)
        }
        await model.load(feed: .all, session: session)
        await refreshR2()
        XCTAssertEqual(count, 1)
        XCTAssertEqual(model.feed, .all)
        await model.load(feed: .r2, session: session)
        await refreshR2()
        XCTAssertEqual(count, 3)
        XCTAssertEqual(model.topics.map(\.id), [3])
        await model.load(feed: .all, session: session)
        await refreshR2()
        XCTAssertEqual(count, 3)
        XCTAssertEqual(model.feed, .all)
        XCTAssertEqual(model.topics.map(\.id), [1])
    }

    @MainActor
    func testConcurrentManualRefreshesShareActiveRequest() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let refresh = Task { await model.refresh(feed: .r2, session: session, source: .pull) }
        await fulfillment(of: [gate.started], timeout: 3)
        await model.refresh(feed: .r2, session: session, source: .button)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .refreshing)
        gate.release()
        await refresh.value
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.topics.map(\.id), [2])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
    }

    @MainActor
    func testCancelledLoadRetainsSuccessTimeAndCanRetry() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let refresh = Task { await model.load(feed: .r2, session: session, force: true) }
        await fulfillment(of: [gate.started], timeout: 3)
        refresh.cancel()
        await refresh.value
        gate.release()
        XCTAssertFalse(model.isLoading)
        XCTAssertEqual(model.topics.map(\.id), [1])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .cancelled)
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        FollowingProtocol.beforeResponse = nil
        await model.refresh(feed: .r2, session: session, source: .button)
        XCTAssertEqual(model.topics.map(\.id), [3])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
    }

    @MainActor
    func testPullRefreshSurvivesCallerCancellation() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let caller = Task { await model.refresh(feed: .r2, session: session, source: .pull) }
        await fulfillment(of: [gate.started], timeout: 3)
        caller.cancel()
        gate.release()
        await caller.value
        XCTAssertTrue(caller.isCancelled)
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.topics.map(\.id), [2])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
        XCTAssertGreaterThan(try XCTUnwrap(model.refreshState(for: .r2).lastSuccess), firstSuccess)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testPullRefreshFromAlreadyCancelledCallerStillFetches() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let caller = Task { @MainActor in
            XCTAssertTrue(Task.isCancelled)
            await model.refresh(feed: .r2, session: session, source: .pull)
        }
        // The main-actor caller cannot begin until this test yields below.
        caller.cancel()
        await caller.value
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.topics.map(\.id), [2])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testButtonDoesNotDuplicateRefreshAfterPullCallerIsCancelled() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { _, deliver in gate.hold(deliver) }
        let caller = Task { await model.refresh(feed: .r2, session: session, source: .pull) }
        await fulfillment(of: [gate.started], timeout: 3)
        caller.cancel()
        await model.refresh(feed: .r2, session: session, source: .button)
        gate.release()
        await caller.value
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.topics.map(\.id), [2])
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
    }

    @MainActor
    func testCancelledPullCallerCannotOverwriteAnotherFeed() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (200, [:], Self.publicPage([count]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        let gate = ResponseGate()
        FollowingProtocol.beforeResponse = { request, deliver in
            if request.url?.path == "/" { gate.hold(deliver) } else { deliver() }
        }
        let caller = Task { await model.refresh(feed: .r2, session: session, source: .pull) }
        await fulfillment(of: [gate.started], timeout: 3)
        caller.cancel()
        await model.load(feed: .all, session: session)
        gate.release()
        await caller.value
        XCTAssertEqual(count, 3)
        XCTAssertEqual(model.feed, .all)
        XCTAssertEqual(model.topics.map(\.id), [3])
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .cancelled)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testCancelledPullCallerCannotCommitPreviousAccount() async throws -> Void {
        let session = V2EXSessionStore(inMemory: true)
        session.save(cookie: "first-fixture", username: "first")
        let gate = ResponseGate()
        var count = 0
        let model = HomeViewModel { base, _, _ in
            count += 1
            let result = try FollowingPage(html: Self.page(Self.row(count)), base: base, page: 1)
            if count == 1 { return result }
            return await withCheckedContinuation { continuation in
                gate.hold { continuation.resume(returning: result) }
            }
        }
        await model.load(feed: .following, session: session)
        let caller = Task { await model.refresh(feed: .following, session: session, source: .pull) }
        await fulfillment(of: [gate.started], timeout: 3)
        caller.cancel()
        session.save(cookie: "second-fixture", username: "second")
        model.synchronize(session: session)
        gate.release()
        await caller.value
        XCTAssertEqual(count, 2)
        XCTAssertTrue(model.topics.isEmpty)
        XCTAssertNil(model.refreshState(for: .following).lastSuccess)
        XCTAssertFalse(model.isLoading)
    }

    @MainActor
    func testTransportCancellationIsNotReportedAsSuccessfulRefresh() async throws -> Void {
        let client = publicClient { _ in (200, [:], Self.publicPage([9])) }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        FollowingProtocol.transportError = URLError(.cancelled)
        await model.refresh(feed: .r2, session: session, source: .pull)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .cancelled)
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        XCTAssertEqual(model.topics.map(\.id), [9])
        XCTAssertFalse(model.isLoading)
        FollowingProtocol.transportError = nil
        await model.refresh(feed: .r2, session: session, source: .retry)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: true))
    }

    @MainActor
    func testRefreshStatusDistinguishesSameRowsFromChangedFields() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            let html = Self.publicPage([9])
            return (200, [:], count < 4 ? html : html.replacingOccurrences(of: "Test topic", with: "Changed title"))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        await model.load(feed: .all, session: session)
        await model.load(feed: .r2, session: session)
        XCTAssertEqual(count, 2)
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        // A real successful request with identical rows still records success.
        await model.refresh(feed: .r2, session: session, source: .button)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: true))
        XCTAssertGreaterThan(try XCTUnwrap(model.refreshState(for: .r2).lastSuccess), firstSuccess)
        await model.refresh(feed: .r2, session: session, source: .button)
        XCTAssertEqual(model.topics.map(\.id), [9])
        XCTAssertEqual(model.topics.first?.title, "Changed title")
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: false))
    }

    @MainActor
    func testFailedRefreshDoesNotAdvanceSuccessTime() async throws -> Void {
        var count = 0
        let client = publicClient { _ in
            count += 1
            return (count == 2 ? 503 : 200, [:], Self.publicPage([9]))
        }
        let session = V2EXSessionStore(inMemory: true)
        let model = HomeViewModel(client: client)
        await model.load(feed: .r2, session: session)
        let firstSuccess = try XCTUnwrap(model.refreshState(for: .r2).lastSuccess)
        await model.refresh(feed: .r2, session: session, source: .pull)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .failed)
        XCTAssertEqual(model.refreshState(for: .r2).lastSuccess, firstSuccess)
        XCTAssertNotNil(model.errorMessage)
        XCTAssertEqual(model.topics.map(\.id), [9])
        await model.refresh(feed: .r2, session: session, source: .retry)
        XCTAssertEqual(model.refreshState(for: .r2).phase, .success(unchanged: true))
        XCTAssertNil(model.errorMessage)
    }

    @MainActor
    func testFollowingPaginationUsesSessionAndPreservesOrder() async throws -> Void {
        let session = V2EXSessionStore(inMemory: true)
        session.save(cookie: "fixture", username: "testuser")
        var pages: [Int] = []
        let model = HomeViewModel { base, page, cookie in
            XCTAssertEqual(cookie, "fixture")
            pages.append(page)
            return try FollowingPage(html: Self.page(page == 1 ? Self.row(9) : Self.row(9) + Self.row(3), next: page == 1 ? 2 : nil), base: base, page: page)
        }
        await model.load(feed: .following, session: session)
        await model.loadMore(feed: .following)
        XCTAssertEqual(pages, [1, 2])
        XCTAssertEqual(model.topics.map(\.id), [9, 3])
        XCTAssertFalse(model.hasMore)
        session.clear()
        model.synchronize(session: session)
        XCTAssertTrue(model.topics.isEmpty)
        await model.load(feed: .following, session: session)
        XCTAssertNotNil(model.errorMessage)
        XCTAssertEqual(pages, [1, 2])
    }

    @MainActor
    func testAccountChangeDiscardsInFlightResponse() async throws -> Void {
        let session = V2EXSessionStore(inMemory: true)
        session.save(cookie: "first", username: "first")
        let model = HomeViewModel { base, page, _ in
            session.save(cookie: "second", username: "second")
            return try FollowingPage(html: Self.page(Self.row(1)), base: base, page: page)
        }
        await model.load(feed: .following, session: session)
        XCTAssertTrue(model.topics.isEmpty)
        XCTAssertFalse(model.isLoading)
        model.synchronize(session: session)
        XCTAssertFalse(model.hasMore)
    }
}

private final class FollowingProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, [String: String], String))?
    static var beforeResponse: ((URLRequest, @escaping () -> Void) -> Void)?
    static var transportError: URLError?
    private let lock = NSRecursiveLock()
    private var stopped = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() -> Void {
        if let error = Self.transportError {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        guard let handler = Self.handler, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let (code, headers, body) = handler(request)
        let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers)!
        let deliver: () -> Void = { [self] in
            lock.lock()
            defer { lock.unlock() }
            guard !stopped else { return }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        if let beforeResponse = Self.beforeResponse { beforeResponse(request, deliver) }
        else { deliver() }
    }
    override func stopLoading() -> Void {
        lock.lock()
        stopped = true
        lock.unlock()
    }
}

private final class ResponseGate {
    let started = XCTestExpectation(description: "Response is held")
    private let lock = NSLock()
    private var delivery: (() -> Void)?
    private var released = false

    func hold(_ delivery: @escaping () -> Void) -> Void {
        lock.lock()
        let deliverNow = released || self.delivery != nil
        if !deliverNow { self.delivery = delivery }
        lock.unlock()
        if deliverNow { delivery() } else { started.fulfill() }
    }

    func release() -> Void {
        lock.lock()
        released = true
        let delivery = self.delivery
        self.delivery = nil
        lock.unlock()
        delivery?()
    }
}
