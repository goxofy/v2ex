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
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() -> Void {
        guard let handler = Self.handler, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let (code, headers, body) = handler(request)
        let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() -> Void {}
}
