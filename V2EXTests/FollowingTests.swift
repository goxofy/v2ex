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
