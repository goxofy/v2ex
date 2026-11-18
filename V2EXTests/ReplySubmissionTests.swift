import XCTest
@testable import V2EX

final class ReplySubmissionTests: XCTestCase {
    private let topicURL = URL(string: "https://www.v2ex.com/t/123")!
    private let cookie = "A=reply-test; PB3_SESSION=test-session"

    private func form(once: String = "12345") -> String {
        "<form method='post' action='/t/123'><textarea name='content'></textarea><input value='\(once)' type='hidden' name='once'></form>"
    }

    private func record(_ id: Int, text: String = "测试回复", author: String = "tester") -> String {
        "<div id='r_\(id)' class='cell'><a href='/member/\(author)'>\(author)</a><div class='reply_content'>\(text)</div></div>"
    }

    private func client(_ steps: [ReplyRequestProtocol.Step]) -> V2EXClient {
        ReplyRequestProtocol.prepare(steps)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ReplyRequestProtocol.self]
        if let host = URL(string: V2EXEndpoint.base)?.host,
           let stale = HTTPCookie(properties: [.domain: host, .path: "/", .name: "A", .value: "old-account"]) {
            config.httpCookieStorage?.setCookie(stale)
        }
        return V2EXClient(webSession: URLSession(configuration: config))
    }

    private func send(_ client: V2EXClient, content: String = "测试回复") async throws {
        try await client.reply(topicID: 123, content: content, cookie: cookie, username: "tester")
    }

    override func tearDown() {
        XCTAssertEqual(ReplyRequestProtocol.remaining, 0, "All expected requests must be sent")
        XCTAssertLessThanOrEqual(ReplyRequestProtocol.posts, 1, "Never replay a reply POST")
        ReplyRequestProtocol.prepare([])
        super.tearDown()
    }

    func testFormTokenIsScopedToReplyForm() throws {
        let html = "<input name='once' value='wrong'><a href='/favorite/topic/123?once=wrong'>收藏</a>" + form(once: "correct")
        XCTAssertEqual(try ReplyPage(html: html).form(topicURL: topicURL).once, "correct")
    }

    func testFormSupportsAttributeOrderQuotesAndOmittedAction() throws {
        for html in [
            "<FORM METHOD=post><TEXTAREA NAME=content></TEXTAREA><INPUT NAME=once VALUE=456></FORM>",
            "<form action=\"/t/123\" method=\"POST\"><textarea class='mle' name='content'></textarea><input name=\"once\" type='hidden' value=\"456\"></form>"
        ] {
            let parsed = try ReplyPage(html: html).form(topicURL: topicURL)
            XCTAssertEqual(parsed.once, "456")
            XCTAssertEqual(parsed.url, topicURL)
        }
    }

    func testRejectsMissingAmbiguousAndUntrustedForms() {
        for html in [
            "<a href='/signout?once=123'>退出</a>",
            form() + form(),
            form().replacingOccurrences(of: "name='once'", with: "name='other'"),
            form().replacingOccurrences(of: "</form>", with: "<input name='once' value='other'></form>"),
            form().replacingOccurrences(of: "action='/t/123'", with: "action='https://evil.example/t/123'"),
            form().replacingOccurrences(of: "action='/t/123'", with: "action='/t/456'"),
            form().replacingOccurrences(of: "method='post'", with: "method='get'")
        ] {
            XCTAssertThrowsError(try ReplyPage(html: html).form(topicURL: topicURL), html)
        }
    }

    func testFormEncodingRoundTripsSpecialCharacters() throws {
        let content = "C++ +1 & = % 中文🙂\n下一行 <tag> #?"
        let body = try XCTUnwrap(String(data: ReplyPage.formBody(content: content, once: "a+b&c"), encoding: .utf8))
        XCTAssertFalse(body.contains("+"))
        XCTAssertTrue(body.contains("%2B"))
        let fields = URLComponents(string: "https://example.test/?" + body)?.queryItems
        XCTAssertEqual(fields?.first(where: { $0.name == "content" })?.value, content)
        XCTAssertEqual(fields?.first(where: { $0.name == "once" })?.value, "a+b&c")
    }

    func testParsesNestedProblemBlocksWithIDOrClassToken() throws {
        for attribute in ["id='problem'", "class='box problem warning'"] {
            let page = ReplyPage(html: "<div \(attribute)><div>验证失败 &amp; 请刷新</div><span>再试</span></div>")
            let message = try XCTUnwrap(page.rejection?.errorDescription)
            XCTAssertTrue(message.contains("验证失败 & 请刷新"))
            XCTAssertTrue(message.contains("再试"))
        }
    }

    func testUserContentAndReflectedDraftCannotBecomeSiteErrorsOrReplies() {
        let html = "<div class='topic_content'><div id='problem'>这是示例</div>你上一次回复是在</div>"
            + record(10, text: "<div id='problem'>引用</div>验证码 captcha")
            + "<textarea name='content'>" + record(11) + "</textarea>"
            + "<script>var example = \"<div id='problem'>错误</div>\";</script>"
        let page = ReplyPage(html: html)
        XCTAssertNil(page.rejection)
        XCTAssertEqual(page.records.map(\.id), [10])
    }

    func testUserContentCannotImpersonateReplyRecordsOrPagination() {
        let page = ReplyPage(html: "<div class='topic_content'>" + record(50)
            + "<a href='/t/123?p=999'>示例链接</a></div><a href='/t/123?p=2'>下一页</a>")
        XCTAssertTrue(page.records.isEmpty)
        XCTAssertEqual(page.lastPageURL(topicURL: topicURL)?.query, "p=2")
        let withoutAuthor = ReplyPage(html: "<div id='r_51'><div class='reply_content'><a href='/member/tester'>tester</a></div></div>")
        XCTAssertTrue(withoutAuthor.records.isEmpty, "A mention is not the reply's author")
    }

    func testConfirmationRequiresNewIDAuthorAndFullContent() {
        let before = ReplyPage(html: record(10))
        for html in [record(10), record(11, author: "someone"), record(11, text: "测试回复但不是同一条"),
                     "<textarea>测试回复</textarea>", "<div class='topic_content'>测试回复</div>"] {
            XCTAssertFalse(ReplyPage(html: html).confirms(content: "测试回复", username: "tester", after: before), html)
        }
        XCTAssertTrue(ReplyPage(html: record(11)).confirms(content: "测试回复", username: "tester", after: before))
    }

    func testBaselineUsesIDsEvenWhenOldReplyContentsAreUnavailable() {
        let before = ReplyPage(html: "<div id='r_20'>已隐藏的回复</div>")
        XCTAssertFalse(ReplyPage(html: record(20)).confirms(content: "测试回复", username: "tester", after: before))
        XCTAssertTrue(ReplyPage(html: record(21)).confirms(content: "测试回复", username: "tester", after: before))
    }

    func testConfirmationHandlesEntitiesMentionsAndLineBreaks() {
        let body = "C++ &amp; &lt;tag&gt;<br>@<a href='/member/other'>other</a>\n中文🙂"
        let after = ReplyPage(html: record(11, text: body))
        XCTAssertTrue(after.confirms(content: "C++ & <tag>\n@other 中文🙂", username: "tester", after: ReplyPage(html: record(10))))
    }

    func testSubmissionUsesFormTokenCorrectHeadersAndEncodedBody() async throws {
        let content = "C++ & 中文\n@other"
        let client = client([
            .init(body: "<input name='once' value='wrong'>" + form(once: "789")),
            .init(method: "POST", body: record(11, text: "C++ &amp; 中文<br>@<a href='/member/other'>other</a>"), expectedBody: ReplyPage.formBody(content: content, once: "789"))
        ])
        try await send(client, content: content)
        XCTAssertEqual(ReplyRequestProtocol.posts, 1)
    }

    func testCustomOriginIsKeptConsistentAcrossRequests() async throws {
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: V2EXEndpoint.customBaseKey)
        defaults.set("https://proxy.example:8443", forKey: V2EXEndpoint.customBaseKey)
        defer { defaults.set(previous, forKey: V2EXEndpoint.customBaseKey) }
        let client = client([.init(body: form()), .init(method: "POST", body: record(11))])
        try await send(client)
    }

    func testConfirmationFindsNewReplyOnNextPage() async throws {
        let client = client([
            .init(body: form() + record(10)),
            .init(method: "POST", status: 303, location: "/t/123#reply11"),
            .init(body: form() + record(10) + "<a class='page_normal' href='/t/123?p=2'>2</a>"),
            .init(path: "/t/123?p=2", body: form() + record(11))
        ])
        try await send(client)
    }

    func testPOSTChallengeDoesNotRetry() async {
        let client = client([
            .init(body: form()),
            .init(method: "POST", body: "<title>Just a moment...</title>", status: 403)
        ])
        do {
            try await send(client)
            XCTFail("Challenge must fail without another POST")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("网页验证")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testServiceErrorAfterPOSTIsUnconfirmed() async {
        let client = client([.init(body: form()), .init(method: "POST", body: "Service Unavailable", status: 503)])
        do {
            try await send(client)
            XCTFail("A service error cannot prove the reply was not committed")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testClosedTopicPreventsPOST() async {
        let client = client([.init(body: "<div>本主题的回复功能已关闭</div>")])
        do {
            try await send(client)
            XCTFail("Closed topic must not submit")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("关闭回复")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testLastPageIsFetchedBeforePostingAndOldReplyDoesNotConfirm() async {
        let first = form() + record(1) + "<a class='page_normal' href='/t/123?p=2'>2</a>"
        let last = form(once: "last") + record(20)
        let client = client([
            .init(body: first),
            .init(path: "/t/123?p=2", body: last),
            .init(method: "POST", body: last, expectedBody: ReplyPage.formBody(content: "测试回复", once: "last"), referer: "/t/123?p=2"),
            .init(path: "/t/123?p=2", body: last)
        ])
        do {
            try await send(client)
            XCTFail("An existing reply on the last page is not a new reply")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testRedirectIsFollowedWithGETAndConfirmsNewReply() async throws {
        let client = client([
            .init(body: form() + record(10)),
            .init(method: "POST", status: 302, location: "/t/123?p=2#reply11"),
            .init(path: "/t/123?p=2", body: form() + record(11))
        ])
        try await send(client)
    }

    func test307NeverReplaysPOST() async {
        let client = client([
            .init(body: form()),
            .init(method: "POST", status: 307, location: "/t/123"),
            .init(body: form())
        ])
        do {
            try await send(client)
            XCTFail("Redirect alone is not proof of submission")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(ReplyRequestProtocol.posts, 1)
    }

    func testHTTP200ProblemReportsServerReasonWithoutRetry() async {
        let client = client([
            .init(body: form()),
            .init(method: "POST", body: "<div id='problem'><ul><li>验证信息已过期</li></ul></div>")
        ])
        do {
            try await send(client)
            XCTFail("Server rejection must fail")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("验证信息已过期")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testCooldownIsReportedWithoutRetry() async {
        let client = client([.init(body: form()), .init(method: "POST", body: "<div>你上一次回复是在 5 秒前</div>")])
        do {
            try await send(client)
            XCTFail("Cooldown must fail")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("频繁")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testForbiddenIsNotAssumedToBeExpiredToken() async {
        let client = client([.init(body: form()), .init(method: "POST", body: "Forbidden", status: 403)])
        do {
            try await send(client)
            XCTFail("Forbidden must fail")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("403")) }
        catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(ReplyRequestProtocol.posts, 1)
    }

    func testLoginRedirectDuringPOSTDoesNotSendAnotherRequest() async {
        let client = client([.init(body: form()), .init(method: "POST", status: 302, location: "/signin")])
        do {
            try await send(client)
            XCTFail("Login redirect must fail")
        } catch V2EXError.sessionExpired {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testTwoFactorRedirectBeforePOSTPreventsSubmission() async {
        let client = client([.init(status: 302, location: "/2fa")])
        do {
            try await send(client)
            XCTFail("Incomplete authentication must fail")
        } catch V2EXError.sessionExpired {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(ReplyRequestProtocol.posts, 0)
    }

    func testLoginFormHTTP200PreventsSubmission() async {
        let client = client([.init(body: "<form method='post' action='/signin'><input name='once' value='123'></form>")])
        do {
            try await send(client)
            XCTFail("Login form must not supply a reply token")
        } catch V2EXError.sessionExpired {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testGuestTopicPagePreventsSubmission() async {
        let client = client([.init(body: "<a class='top' href='/signin'>登录</a><div class='topic_content'>公开主题</div>")])
        do {
            try await send(client)
            XCTFail("Public topic without an authenticated session must not submit")
        } catch V2EXError.sessionExpired {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testLoginFormWithoutActionIsRecognized() {
        let page = ReplyPage(html: "<form method='post'><input type='password' name='random-field'></form>")
        guard case .sessionExpired? = page.rejection else {
            XCTFail("Password form is authentication, not a changed reply form")
            return
        }
    }

    func testChallengeIsReportedBeforePosting() async {
        let client = client([.init(body: "<title>Just a moment...</title><div>Checking your browser</div>")])
        do {
            try await send(client)
            XCTFail("Browser challenge must fail")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("网页验证")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testMissingFormIsNotMisreportedAsExpiredSession() async {
        let client = client([.init(body: "<div>主题存在但没有回复表单</div>")])
        do {
            try await send(client)
            XCTFail("Missing form must fail")
        } catch V2EXError.replyFailed(let message) { XCTAssertTrue(message.contains("回复表单")) }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testCrossOriginRedirectNeverReceivesCookie() async {
        let client = client([.init(body: form()), .init(method: "POST", status: 302, location: "https://evil.example/t/123")])
        do {
            try await send(client)
            XCTFail("Untrusted redirect must not be followed")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testDifferentTopicRedirectDoesNotConfirm() async {
        let client = client([.init(body: form()), .init(method: "POST", status: 302, location: "/t/456")])
        do {
            try await send(client)
            XCTFail("Another topic must not confirm the reply")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testUnknownResponseOnlyChecksWithGET() async {
        let client = client([.init(body: form()), .init(method: "POST", body: "OK"), .init(body: form())])
        do {
            try await send(client)
            XCTFail("HTTP 200 alone is not confirmation")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testTimeoutAfterPOSTIsUnconfirmedWithoutRetry() async {
        let client = client([.init(body: form()), .init(method: "POST", error: .timedOut)])
        do {
            try await send(client)
            XCTFail("Timeout must preserve the draft as unconfirmed")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testConfirmationReadFailureIsUnconfirmed() async {
        let client = client([.init(body: form()), .init(method: "POST", status: 302, location: "/t/123"), .init(error: .networkConnectionLost)])
        do {
            try await send(client)
            XCTFail("A failed GET cannot prove the POST failed")
        } catch V2EXError.replyUnconfirmed {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testInvalidInputsDoNotSendRequests() async {
        let client = client([])
        do {
            try await send(client, content: " \n ")
            XCTFail("Empty reply must fail")
        } catch V2EXError.replyFailed {} catch { XCTFail("Unexpected error: \(error)") }
        do {
            try await client.reply(topicID: 123, content: "test", cookie: "", username: "tester")
            XCTFail("Missing cookie must fail")
        } catch V2EXError.sessionExpired {} catch { XCTFail("Unexpected error: \(error)") }
    }
}

private final class ReplyRequestProtocol: URLProtocol, @unchecked Sendable {
    struct Step {
        var path = "/t/123"
        var method = "GET"
        var body = ""
        var status = 200
        var location: String? = nil
        var error: URLError.Code? = nil
        var expectedBody: Data? = nil
        var referer = "/t/123"
    }

    private static let lock = NSLock()
    private static var steps: [Step] = []
    private static var postCount = 0

    static func prepare(_ responses: [Step]) {
        lock.lock()
        defer { lock.unlock() }
        steps = responses
        postCount = 0
    }

    static var remaining: Int {
        lock.lock()
        defer { lock.unlock() }
        return steps.count
    }

    static var posts: Int {
        lock.lock()
        defer { lock.unlock() }
        return postCount
    }

    private static func next(method: String?) -> Step? {
        lock.lock()
        defer { lock.unlock() }
        if method == "POST" { postCount += 1 }
        return steps.isEmpty ? nil : steps.removeFirst()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let step = Self.next(method: request.httpMethod), let url = request.url else {
            XCTFail("Unexpected request")
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.fragment = nil
        XCTAssertEqual(components?.url?.absoluteString, V2EXEndpoint.base + step.path)
        XCTAssertEqual(request.httpMethod, step.method)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cookie"), "A=reply-test; PB3_SESSION=test-session")
        XCTAssertFalse(request.httpShouldHandleCookies)
        XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
        XCTAssertTrue(request.value(forHTTPHeaderField: "User-Agent")?.contains("Safari") == true)
        if step.method == "POST" {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Origin"), V2EXEndpoint.base)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Referer"), V2EXEndpoint.base + step.referer)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/x-www-form-urlencoded")
            if let expectedBody = step.expectedBody {
                XCTAssertEqual(Self.body(of: request), expectedBody)
            }
        }
        if let error = step.error {
            client?.urlProtocol(self, didFailWithError: URLError(error))
            return
        }
        let headers = step.location.map { ["Location": $0] }
        let response = HTTPURLResponse(url: url, statusCode: step.status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(step.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func body(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
