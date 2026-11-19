import XCTest
@testable import V2EX

final class TopicMoveTests: XCTestCase {
    private let base = URL(string: "https://www.v2ex.com")!
    private let target = MoveNode(name: "sandbox", title: "沙盒")
    private var service: TopicMoveService!
    private let form = """
    <div id="Main"><div class="box"><div class="inner">
    <a class="node-xl" href="javascript:chooseNode('sandbox')">沙盒</a>
    <form method="post" action="/move/topic/123"><select name="destination"></select>
    <input type="text" name="memo"><input type="hidden" name="once" value="safe-token"></form>
    </div></div></div>
    """

    override func setUp() {
        super.setUp()
        MoveProtocol.responses = []
        MoveProtocol.requests = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MoveProtocol.self]
        service = TopicMoveService(base: base, configuration: configuration, wait: { _ in })
    }

    override func tearDown() {
        XCTAssertLessThanOrEqual(MoveProtocol.requests.filter { $0.httpMethod == "POST" }.count, 1)
        service = nil
        super.tearDown()
    }

    private func topic(_ node: String = "qna", allowed: Bool = true, extra: String = "") -> String {
        """
        <div id="Main"><div class="box"><div class="header"><a href="/go/\(node)">\(node)</a><h1>Fixture title</h1>
        \(allowed ? "<small><a class='op' href='/move/topic/123'>MOVE</a></small>" : "")</div>\(extra)</div></div>
        """
    }

    private func mobile(_ html: String) -> String {
        "<div id=\"Wrapper\">" + html.replacingOccurrences(of: "<div id=\"Main\">", with: "<div class=\"content\">") + "</div>"
    }

    private func enqueue(_ html: String, status: Int = 200, location: String? = nil) {
        MoveProtocol.responses.append((status, html, location))
    }

    private func move(current: String = "qna", valid: Bool = true) async throws -> MoveNode {
        try await service.move(id: 123, cookie: "session=fake", expectedNode: current, destination: target,
                               memo: "中文 + & =\n备注", sessionCurrent: { valid })
    }

    private func expect(_ expected: MoveFailure, block: () async throws -> Void) async {
        do { try await block(); XCTFail("Expected failure") }
        catch { XCTAssertEqual(error as? MoveFailure, expected) }
    }

    func testPermissionRequiresHeaderLink() async throws {
        enqueue(topic())
        let result = try await service.permission(id: 123, cookie: "session=fake")
        XCTAssertTrue(result.canMove)
    }
    private func expectPermissionIssue(_ stage: MovePermissionIssue.Stage, _ reason: MovePermissionIssue.Reason) async {
        do {
            _ = try await service.permission(id: 123, cookie: "session=fake")
            XCTFail("Expected permission failure")
        } catch {
            XCTAssertEqual(error as? MovePermissionIssue, MovePermissionIssue(stage: stage, reason: reason))
        }
        XCTAssertTrue(MoveProtocol.requests.allSatisfy { $0.httpMethod == "GET" })
    }

    func testOrdinaryMemberHasNoPermission() async {
        enqueue(topic(allowed: false)); enqueue("", status: 403)
        await expectPermissionIssue(.form, .http(403))
    }
    func testBodyCannotForgePermission() async {
        enqueue(topic(allowed: false, extra: "<div class='topic_content'><a class='op' href='/move/topic/123'>MOVE</a></div>"))
        enqueue("", status: 403)
        await expectPermissionIssue(.form, .http(403))
    }
    func testMobilePageWithoutMoveLinkUsesValidForm() async throws {
        enqueue(mobile(topic(allowed: false))); enqueue(mobile(form))
        let result = try await service.permission(id: 123, cookie: "session=fake")
        XCTAssertTrue(result.canMove)
        XCTAssertEqual(result.node.name, "qna")
        XCTAssertEqual(MoveProtocol.requests.map { $0.url?.path }, ["/t/123", "/move/topic/123"])
        XCTAssertTrue(MoveProtocol.requests.allSatisfy { $0.httpMethod == "GET" })
    }
    func testMobileHeaderLinkGrantsPermissionWithoutFormRequest() async throws -> Void {
        enqueue(mobile(topic()))
        let result = try await service.permission(id: 123, cookie: "session=fake")
        XCTAssertTrue(result.canMove)
        XCTAssertEqual(result.node.name, "qna")
        XCTAssertEqual(MoveProtocol.requests.map(\.httpMethod), ["GET"])
    }
    func testMobilePrepareLoadsFormAndShortcuts() async throws -> Void {
        enqueue(mobile(topic())); enqueue(mobile(form))
        enqueue(#"[{"name":"sandbox","title":"沙盒","aliases":["test"]}]"#)
        let result = try await service.prepare(id: 123, cookie: "session=fake")
        XCTAssertEqual(result.form.shortcuts, [target])
        XCTAssertEqual(result.form.hidden["once"], "safe-token")
        XCTAssertTrue(result.nodes[0].matches("TEST"))
    }
    func testMobileMoveRevalidatesAndConfirmsWithoutReposting() async throws -> Void {
        enqueue(mobile(topic(allowed: false))); enqueue(mobile(form)); enqueue(mobile(form))
        enqueue("", status: 302, location: "/t/123")
        enqueue(mobile(topic(allowed: false))); enqueue(mobile(topic("sandbox", allowed: false)))
        let result = try await move()
        XCTAssertEqual(result.name, "sandbox")
        XCTAssertEqual(MoveProtocol.requests.map(\.httpMethod), ["GET", "GET", "GET", "POST", "GET", "GET"])
    }
    func testMobileOrdinaryAccountCannotGainPermission() async -> Void {
        enqueue(mobile(topic(allowed: false))); enqueue("", status: 403)
        await expectPermissionIssue(.form, .http(403))
    }
    func testMobileProblemCannotGrantPermission() async -> Void {
        enqueue(mobile(topic(extra: "<div class='problem'>Unavailable</div>")))
        await expectPermissionIssue(.topic, .rejected)
    }
    func testMobileRequiresUniqueDirectContainers() throws -> Void {
        for source in [topic(), form] {
            let html = mobile(source)
            let invalid = [
                html.replacingOccurrences(of: "id=\"Wrapper\"", with: "id=\"Other\""),
                html.replacingOccurrences(of: "<div id=\"Wrapper\">", with: "<div id=\"Wrapper\"></div><div id=\"Wrapper\">"),
                html.replacingOccurrences(of: "<div class=\"content\">", with: "<div class=\"content\"></div><div class=\"content\">"),
                html.replacingOccurrences(of: "class=\"content\"", with: "class=\"other\""),
                "<div id='Wrapper'><section>" + source.replacingOccurrences(of: "id=\"Main\"", with: "class=\"content\"") + "</section></div>",
                "<div id='Wrapper'><div class='content'><section>" + source.replacingOccurrences(of: "id=\"Main\"", with: "class=\"other\"") + "</section></div></div>",
                "<span id='Wrapper'></span>" + html,
                "<div id='Main'></div>" + html,
                "<div id='Main'></div><div id='Main'></div>" + html,
            ]
            for value in invalid {
                let page = TopicMovePage(html: value, base: base)
                XCTAssertThrowsError(try page.topic(id: 123))
                XCTAssertThrowsError(try page.form(id: 123))
            }
        }
    }
    func testMobileContentCannotForgeHeaderOrForm() throws -> Void {
        for excluded in ["topic_content", "reply_content", "topic_append", "subtle"] {
            let forged = "<div class='\(excluded)'>" + mobile(topic()) + mobile(form) + "</div>"
            let page = TopicMovePage(html: mobile(topic(allowed: false, extra: forged)), base: base)
            XCTAssertFalse(try page.topic(id: 123).canMove)
            XCTAssertThrowsError(try page.form(id: 123))
        }
    }
    func testMobileFormStillRejectsInvalidControlsAndActions() throws -> Void {
        let invalid = [
            form.replacingOccurrences(of: "action=\"/move/topic/123\"", with: "action=\"https://example.invalid/move/topic/123\""),
            form.replacingOccurrences(of: "/move/topic/123", with: "/move/topic/456"),
            form.replacingOccurrences(of: "</form>", with: "<input name='memo'></form>"),
            form.replacingOccurrences(of: "</form>", with: "<input type='hidden' name='unknown' value='1'></form>"),
        ]
        for html in invalid {
            XCTAssertThrowsError(try TopicMovePage(html: mobile(html), base: base).form(id: 123))
        }
    }
    func testDesktopMainInsideWrapperStillParses() throws -> Void {
        let topicHTML = "<div id='Wrapper'><div class='content'>" + topic() + "</div></div>"
        XCTAssertTrue(try TopicMovePage(html: topicHTML, base: base).topic(id: 123).canMove)
        let formHTML = "<div id='Wrapper'><div class='content'>" + form + "</div></div>"
        XCTAssertEqual(try TopicMovePage(html: formHTML, base: base).form(id: 123).shortcuts, [target])
    }
    func testFallbackSignInIsSessionFailure() async {
        enqueue(topic(allowed: false)); enqueue("", status: 302, location: "/signin")
        await expectPermissionIssue(.form, .session)
    }
    func testFallbackChallengeDoesNotGrantPermission() async {
        enqueue(topic(allowed: false)); enqueue("<title>Just a moment</title>" + form)
        await expectPermissionIssue(.form, .verification)
    }
    func testFallbackCrossOriginFormDoesNotGrantPermission() async {
        enqueue(topic(allowed: false)); enqueue(form.replacingOccurrences(of: "action=\"/move/topic/123\"", with: "action=\"https://example.invalid/move/topic/123\""))
        await expectPermissionIssue(.form, .structure)
    }
    func testFallbackCrossOriginRedirectIsNotFollowed() async {
        enqueue(topic(allowed: false)); enqueue("", status: 302, location: "https://example.invalid/move/topic/123")
        await expectPermissionIssue(.form, .redirect)
        XCTAssertEqual(MoveProtocol.requests.count, 2)
    }
    func testFallbackBodyFormDoesNotGrantPermission() async {
        enqueue(topic(allowed: false)); enqueue("<div class='topic_content'>" + form + "</div>")
        await expectPermissionIssue(.form, .structure)
    }
    func testTopicStructureFailureHasDiagnosticCode() async {
        enqueue("<div id='Main'></div>")
        await expectPermissionIssue(.topic, .structure)
        XCTAssertEqual(MoveProtocol.requests.count, 1)
    }
    func testRateLimitedTopicHasDiagnosticStatus() async {
        enqueue("", status: 429)
        await expectPermissionIssue(.topic, .http(429))
    }
    func testTransportFailureHasDiagnosticCode() async {
        await expectPermissionIssue(.topic, .network)
    }
    func testBodyCannotForgeWholeHeader() throws {
        let html = topic(allowed: false, extra: "<div class='reply_content'><div id='Main'><div class='box'><div class='header'><h1>x</h1><a class='op' href='/move/topic/123'>MOVE</a></div></div></div></div>")
        XCTAssertFalse(try TopicMovePage(html: html, base: base).topic(id: 123).canMove)
    }
    func testUntrustedAndWrongTopicLinksAreNotPermissions() throws {
        for link in ["https://example.invalid/move/topic/123", "/move/topic/456", "/move/topic/123?other=1"] {
            XCTAssertFalse(try TopicMovePage(html: topic().replacingOccurrences(of: "/move/topic/123", with: link), base: base).topic(id: 123).canMove)
        }
    }
    func testUnknownTextareaFailsClosed() throws {
        let html = form.replacingOccurrences(of: "</form>", with: "<textarea name='new_control'>text</textarea></form>")
        XCTAssertThrowsError(try TopicMovePage(html: html, base: base).form(id: 123))
    }
    func testDuplicateMainFailsClosed() throws {
        XCTAssertThrowsError(try TopicMovePage(html: topic() + "<div id='Main'></div>", base: base).topic(id: 123))
    }
    func testPrepareLoadsShortcutsAndSearchAliases() async throws {
        enqueue(topic()); enqueue(form)
        enqueue(#"[{"name":"sandbox","title":"沙盒","aliases":["test"]}]"#)
        let result = try await service.prepare(id: 123, cookie: "session=fake")
        XCTAssertEqual(result.form.shortcuts, [target])
        XCTAssertTrue(result.nodes[0].matches("TEST"))
        XCTAssertEqual(result.form.hidden["once"], "safe-token")
    }
    func testMoveWithoutHeaderLinkRevalidatesFormAndPostsOnce() async throws {
        enqueue(topic(allowed: false)); enqueue(form); enqueue(form)
        enqueue("", status: 302, location: "/t/123"); enqueue(topic("sandbox", allowed: false))
        let result = try await move()
        XCTAssertEqual(result.name, "sandbox")
        XCTAssertEqual(MoveProtocol.requests.map(\.httpMethod), ["GET", "GET", "GET", "POST", "GET"])
    }
    func testDirect200StillUsesReadOnlyVerification() async throws {
        enqueue(topic()); enqueue(form); enqueue(topic("sandbox")); enqueue(topic("sandbox"))
        let result = try await move()
        XCTAssertEqual(result.name, "sandbox")
        XCTAssertEqual(MoveProtocol.requests.map(\.httpMethod), ["GET", "GET", "POST", "GET"])
    }
    func test307IsNeverReplayed() async throws {
        enqueue(topic()); enqueue(form); enqueue("", status: 307, location: "/t/123"); enqueue(topic("sandbox"))
        _ = try await move()
        XCTAssertEqual(MoveProtocol.requests.map(\.httpMethod), ["GET", "GET", "POST", "GET"])
    }
    func testDelayedConfirmationOnlyRetriesGet() async throws {
        enqueue(topic()); enqueue(form); enqueue("", status: 302, location: "/t/123")
        enqueue(topic()); enqueue(topic()); enqueue(topic("sandbox"))
        _ = try await move()
        XCTAssertEqual(MoveProtocol.requests.count, 6)
    }
    func test503NeverReplaysPost() async throws {
        enqueue(topic()); enqueue(form); enqueue("", status: 503); enqueue(topic("sandbox"))
        _ = try await move()
        XCTAssertEqual(MoveProtocol.requests.count, 4)
    }
    func test408NeverReplaysPost() async throws {
        enqueue(topic()); enqueue(form); enqueue("", status: 408); enqueue(topic("sandbox"))
        _ = try await move()
    }
    func testForbiddenStopsWithoutRetry() async {
        enqueue(topic()); enqueue(form); enqueue("", status: 403)
        await expect(.denied) { _ = try await self.move() }
        XCTAssertEqual(MoveProtocol.requests.count, 3)
    }
    func testPermissionRevokedPreventsPost() async {
        enqueue(topic(allowed: false)); enqueue("", status: 403)
        await expect(.denied) { _ = try await self.move() }
    }
    func testChangedNodeRequiresConfirmationAgain() async {
        enqueue(topic("other"))
        await expect(.changed) { _ = try await self.move() }
        XCTAssertEqual(MoveProtocol.requests.count, 1)
    }
    func testChangedSessionNeverSendsRequest() async {
        await expect(.session) { _ = try await self.move(valid: false) }
        XCTAssertTrue(MoveProtocol.requests.isEmpty)
    }
    func testInvalidCookieNeverSendsRequest() async {
        await expect(.session) { _ = try await self.service.permission(id: 123, cookie: "bad\nCookie: injected") }
        XCTAssertTrue(MoveProtocol.requests.isEmpty)
    }
    func testCrossOriginFormNeverPosts() async {
        enqueue(topic()); enqueue(form.replacingOccurrences(of: "action=\"/move/topic/123\"", with: "action=\"https://example.invalid/move/topic/123\""))
        await expect(.unavailable) { _ = try await self.move() }
    }
    func testDuplicateMemoIsRejected() throws {
        XCTAssertThrowsError(try TopicMovePage(html: form.replacingOccurrences(of: "</form>", with: "<input name='memo'></form>"), base: base).form(id: 123))
    }
    func testUnknownHiddenFieldIsRejected() throws {
        XCTAssertThrowsError(try TopicMovePage(html: form.replacingOccurrences(of: "</form>", with: "<input type='hidden' name='unrecognized' value='1'></form>"), base: base).form(id: 123))
    }
    func testCrossOriginRedirectRemainsUnconfirmed() async {
        enqueue(topic()); enqueue(form); enqueue("", status: 302, location: "https://example.invalid/t/123")
        await expect(.unconfirmed) { _ = try await self.move() }
        XCTAssertEqual(MoveProtocol.requests.count, 3)
    }
    func testBodyNodeLinkDoesNotConfirmSuccess() async {
        enqueue(topic()); enqueue(form); enqueue("", status: 302, location: "/t/123")
        for _ in 0..<3 { enqueue(topic(extra: "<div class='topic_content'><a href='/go/sandbox'>沙盒</a></div>")) }
        await expect(.unconfirmed) { _ = try await self.move() }
    }
    func testTransientReadFailureCanRecover() async throws {
        enqueue(topic()); enqueue(form); enqueue("", status: 302, location: "/t/123"); enqueue("", status: 503); enqueue(topic("sandbox"))
        _ = try await move()
    }
    #if os(macOS)
    func testRealTransportSendsBodyAndNeverReplays503() async throws {
        guard let fixture = ProcessInfo.processInfo.environment["V2EX_MOVE_HTTP_FIXTURE"] else {
            throw XCTSkip("Run with tests/check-reply-submission.py for the loopback fixture")
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["-I", fixture]
        let pipe = Pipe()
        process.standardOutput = pipe
        try process.run()
        defer { process.terminate(); process.waitUntilExit() }
        let data = pipe.fileHandleForReading.availableData
        let port = try XCTUnwrap(Int(String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)))
        let local = URL(string: "http://127.0.0.1:\(port)")!
        let client = TopicMoveService(base: local, wait: { _ in })
        let result = try await client.move(id: 123, cookie: "session=fake", expectedNode: "qna", destination: target,
                                           memo: "中文 + & =\n备注", sessionCurrent: { true })
        XCTAssertEqual(result.name, "sandbox")
        let (records, _) = try await URLSession.shared.data(from: local.appendingPathComponent("records"))
        let entries = try XCTUnwrap(JSONSerialization.jsonObject(with: records) as? [[String: String]])
        XCTAssertEqual(entries.map { $0["method"] }, ["GET", "GET", "POST", "GET"])
        let post = try XCTUnwrap(entries.first { $0["method"] == "POST" })
        XCTAssertEqual(post["cookie"], "session=fake")
        var components = URLComponents()
        components.percentEncodedQuery = post["body"]
        XCTAssertEqual(components.queryItems?.first { $0.name == "memo" }?.value, "中文 + & =\n备注")
    }
    #endif

    func testSameNodeDoesNotPost() async throws {
        enqueue(topic("sandbox"))
        _ = try await move(current: "sandbox")
        XCTAssertEqual(MoveProtocol.requests.count, 1)
    }
}

private final class MoveProtocol: URLProtocol {
    static var responses: [(Int, String, String?)] = []
    static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        guard !Self.responses.isEmpty else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        let (status, html, location) = Self.responses.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: location.map { ["Location": $0] } ?? [:])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(html.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
