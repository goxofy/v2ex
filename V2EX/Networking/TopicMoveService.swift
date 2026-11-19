import Foundation

struct MovePreparation {
    let topic: MoveTopic
    let form: MoveForm
    let nodes: [MoveNode]
}

final class TopicMoveService {
    private let base: URL
    private let session: URLSession
    init(base: URL, configuration: URLSessionConfiguration = .ephemeral) {
        self.base = base
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 15
        session = URLSession(configuration: configuration)
    }

    deinit { session.invalidateAndCancel() }

    func permission(id: Int, cookie: String) async throws -> MoveTopic {
        try validate(id: id, cookie: cookie)
        let page = try await permissionPage("/t/\(id)", cookie: cookie, stage: .topic)
        let topic: MoveTopic
        do { topic = try page.topic(id: id) }
        catch { throw permissionIssue(error, stage: .topic) }
        if topic.canMove { return topic }
        // Mobile pages may omit the desktop MOVE link. Only a valid server-owned form can replace that evidence.
        let formPage = try await permissionPage("/move/topic/\(id)", cookie: cookie, stage: .form)
        do { _ = try formPage.form(id: id) }
        catch { throw permissionIssue(error, stage: .form) }
        return MoveTopic(node: topic.node, canMove: true)
    }

    func prepare(id: Int, cookie: String) async throws -> MovePreparation {
        do {
            let topic = try await permission(id: id, cookie: cookie)
            guard topic.canMove else { throw MoveFailure.denied }
            let form = try TopicMovePage(html: await get("/move/topic/\(id)", cookie: cookie), base: base).form(id: id)
            let raw = try await get("/api/nodes/list.json?fields=name,title,topics,aliases&sort_by=topics&reverse=1&_=\(UUID().uuidString)", cookie: cookie)
            guard let values = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] else { throw MoveFailure.unavailable }
            let nodes = values.compactMap { value -> MoveNode? in
                guard let name = value["name"] as? String, TopicMovePage.validSlug(name),
                      let title = value["title"] as? String, !title.isEmpty else { return nil }
                let aliases = value["aliases"] as? [String] ?? (value["aliases"] as? String).map { [$0] } ?? []
                return MoveNode(name: name, title: title, aliases: aliases)
            }
            guard !nodes.isEmpty else { throw MoveFailure.unavailable }
            var seen: Set<String> = []
            return MovePreparation(topic: topic, form: form, nodes: (nodes + form.shortcuts).filter { seen.insert($0.name).inserted })
        } catch { throw safe(error) }
    }

    func move(id: Int, cookie: String, expectedNode: String, destination: MoveNode, memo: String,
              sessionCurrent: @MainActor () -> Bool) async throws -> MoveSubmission {
        var submitted = false
        do {
            try validate(id: id, cookie: cookie)
            guard TopicMovePage.validSlug(destination.name), memo.count <= 2000 else { throw MoveFailure.unavailable }
            guard await sessionCurrent() else { throw MoveFailure.session }
            let topic = try await permission(id: id, cookie: cookie)
            guard topic.canMove else { throw MoveFailure.denied }
            guard topic.node.name == expectedNode else { throw MoveFailure.changed }
            if topic.node.name == destination.name { return .unchanged(topic.node) }
            let form = try TopicMovePage(html: await get("/move/topic/\(id)", cookie: cookie), base: base).form(id: id)
            guard await sessionCurrent() else { throw MoveFailure.session }
            var request = try request("/move/topic/\(id)", cookie: cookie)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            var components = URLComponents()
            components.queryItems = [URLQueryItem(name: "destination", value: destination.name), URLQueryItem(name: "memo", value: memo)] +
                form.hidden.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            let body = Data((components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
            // A non-rewindable body and refusal to supply another stream prevent transparent POST replay.
            request.httpBodyStream = InputStream(data: body)
            request.setValue(String(body.count), forHTTPHeaderField: "Content-Length")
            try Task.checkCancellation()
            submitted = true
            let (data, raw) = try await session.data(for: request, delegate: MoveRequestDelegate())
            guard let http = raw as? HTTPURLResponse else { throw MoveFailure.connection }
            if [401, 403].contains(http.statusCode) { throw MoveFailure.denied }
            if http.statusCode == 408 || http.statusCode >= 500 { throw MoveFailure.server }
            if (300..<400).contains(http.statusCode) {
                guard [301, 302, 303, 307, 308].contains(http.statusCode),
                      let location = http.value(forHTTPHeaderField: "Location"),
                      let target = URL(string: location, relativeTo: base)?.absoluteURL else { throw MoveFailure.response }
                if ReplyPage.sameOrigin(target, base), ["/signin", "/2fa"].contains(target.path) { throw MoveFailure.session }
                guard allowed(target, path: "/t/\(id)") else { throw MoveFailure.response }
            } else {
                guard (200..<300).contains(http.statusCode) else { throw MoveFailure.rejected }
                guard let html = String(data: data, encoding: .utf8) else { throw MoveFailure.response }
                let page = TopicMovePage(html: html, base: base)
                if page.requiresVerification { throw MoveFailure.verification }
                try page.checkPage()
            }
            guard await sessionCurrent() else { throw MoveFailure.session }
            return .submitted(destination)
        } catch {
            if error.isCancellation { throw error }
            if let issue = error as? MovePermissionIssue { throw issue.operationFailure }
            throw (error as? MoveFailure) ?? (submitted ? MoveFailure.connection : MoveFailure.unavailable)
        }
    }

    func currentNode(id: Int, cookie: String) async throws -> MoveNode {
        try validate(id: id, cookie: cookie)
        return try TopicMovePage(html: await get("/t/\(id)", cookie: cookie, timeout: 5), base: base).topic(id: id).node
    }

    private func validate(id: Int, cookie: String) throws {
        guard id > 0, !cookie.isEmpty, cookie.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }) else { throw MoveFailure.session }
    }

    private func safe(_ error: Error) -> Error {
        if error.isCancellation { return error }
        if let issue = error as? MovePermissionIssue { return issue.operationFailure }
        return (error as? MoveFailure) ?? MoveFailure.unavailable
    }

    private func permissionPage(_ path: String, cookie: String, stage: MovePermissionIssue.Stage) async throws -> TopicMovePage {
        do {
            let html = try await get(path, cookie: cookie, diagnosticStage: stage)
            let page = TopicMovePage(html: html, base: base)
            if page.requiresVerification { throw MovePermissionIssue(stage: stage, reason: .verification) }
            return page
        } catch {
            if error.isCancellation { throw error }
            if let issue = error as? MovePermissionIssue { throw issue }
            if let failure = error as? MoveFailure { throw permissionIssue(failure, stage: stage) }
            throw MovePermissionIssue(stage: stage, reason: .network)
        }
    }

    private func permissionIssue(_ error: Error, stage: MovePermissionIssue.Stage) -> MovePermissionIssue {
        let reason: MovePermissionIssue.Reason
        switch error as? MoveFailure {
        case .session: reason = .session
        case .rejected: reason = .rejected
        default: reason = .structure
        }
        return MovePermissionIssue(stage: stage, reason: reason)
    }

    private func get(_ path: String, cookie: String, timeout: TimeInterval = 15,
                     diagnosticStage: MovePermissionIssue.Stage? = nil) async throws -> String {
        var request = try request(path, cookie: cookie)
        request.timeoutInterval = timeout
        let expected = request.url!.path
        for _ in 0..<4 {
            let (data, raw) = try await session.data(for: request, delegate: MoveRequestDelegate())
            guard let http = raw as? HTTPURLResponse else { throw MoveFailure.unavailable }
            if http.statusCode == 200 {
                guard let html = String(data: data, encoding: .utf8) else { throw MoveFailure.unavailable }
                return html
            }
            if let stage = diagnosticStage, !(300..<400).contains(http.statusCode) {
                throw MovePermissionIssue(stage: stage, reason: .http(http.statusCode))
            }
            if [401, 403].contains(http.statusCode) { throw MoveFailure.denied }
            guard [301, 302, 303, 307, 308].contains(http.statusCode),
                  let location = http.value(forHTTPHeaderField: "Location"),
                  let target = URL(string: location, relativeTo: request.url)?.absoluteURL else {
                if let stage = diagnosticStage { throw MovePermissionIssue(stage: stage, reason: .redirect) }
                throw MoveFailure.unavailable
            }
            if ["/signin", "/2fa"].contains(target.path) { throw MoveFailure.session }
            guard allowed(target, path: expected) else {
                if let stage = diagnosticStage { throw MovePermissionIssue(stage: stage, reason: .redirect) }
                throw MoveFailure.unavailable
            }
            request.url = target
        }
        if let stage = diagnosticStage { throw MovePermissionIssue(stage: stage, reason: .redirect) }
        throw MoveFailure.unavailable
    }

    private func allowed(_ url: URL, path: String) -> Bool {
        ReplyPage.sameOrigin(url, base) && url.path == path && url.user == nil && url.password == nil && url.fragment == nil &&
            (url.query == nil || path.hasPrefix("/api/"))
    }

    private func request(_ path: String, cookie: String) throws -> URLRequest {
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL, ReplyPage.sameOrigin(url, base) else { throw MoveFailure.unavailable }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpShouldHandleCookies = false
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        request.setValue(base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), forHTTPHeaderField: "Origin")
        request.setValue(url.absoluteString, forHTTPHeaderField: "Referer")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        return request
    }
}

private final class MoveRequestDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        completionHandler(challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust ? .performDefaultHandling : .cancelAuthenticationChallenge, nil)
    }
}
