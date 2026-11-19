import Foundation

struct FollowingPage {
    let topics: [V2Topic]
    let hasMore: Bool

    init(html: String, base: URL, page: Int) throws {
        guard page > 0, page < Int.max else { throw V2EXError.decoding("关注列表页码无效") }
        let tree = MoveElement.parse(html)
        let all = tree.descendants()
        if all.contains(where: {
            ($0.tag == "input" && $0.attributes["type"]?.lowercased() == "password") ||
            ($0.tag == "form" && ["/signin", "/2fa"].contains(Self.localURL($0.attributes["action"], base: base)?.path ?? ""))
        }) {
            throw V2EXError.sessionExpired
        }
        if TopicMovePage(html: html, base: base).requiresVerification {
            throw V2EXError.decoding("网页要求验证，请先完成网页登录验证")
        }
        let mains = all.filter { $0.attributes["id"] == "Main" }
        let main: MoveElement
        if mains.count == 1 { main = mains[0] }
        else if mains.isEmpty {
            let wrappers = all.filter { $0.attributes["id"] == "Wrapper" }
            guard wrappers.count == 1 else { throw V2EXError.decoding("未识别关注列表结构") }
            let contents = wrappers[0].children.filter { $0.tag == "div" && $0.hasClass("content") }
            guard contents.count == 1 else { throw V2EXError.decoding("未识别关注列表结构") }
            main = contents[0]
        } else { throw V2EXError.decoding("未识别关注列表结构") }
        guard !main.descendants().contains(where: { $0.hasClass("problem") || $0.attributes["id"] == "problem" }) else {
            throw V2EXError.decoding("官网未能返回关注列表，请稍后重试")
        }
        var seen: Set<Int> = []
        var parsed: [V2Topic] = []
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss XXXXX"
        for cell in main.descendants("div").filter({ $0.hasClass("cell") }) {
            let titles = cell.descendants().filter { $0.hasClass("item_title") }
            guard titles.count == 1, let link = titles[0].descendants("a").first,
                  let url = Self.localURL(link.attributes["href"], base: base), url.query == nil,
                  url.path.range(of: "^/t/[1-9][0-9]*$", options: .regularExpression) != nil,
                  let id = Int(url.path.dropFirst(3)), id > 0,
                  !link.text.isEmpty, seen.insert(id).inserted else { continue }
            let node = cell.descendants("a").first { $0.hasClass("node") }.flatMap { link -> V2Node? in
                guard let url = Self.localURL(link.attributes["href"], base: base), url.path.hasPrefix("/go/") else { return nil }
                let name = String(url.path.dropFirst(4))
                guard TopicMovePage.validSlug(name) else { return nil }
                return .stub(name: name, title: link.text)
            }
            let author = cell.descendants("strong").flatMap { $0.descendants("a") }.first.flatMap { link -> V2Member? in
                guard let url = Self.localURL(link.attributes["href"], base: base), url.path.hasPrefix("/member/") else { return nil }
                let name = String(url.path.dropFirst(8))
                guard name.range(of: "^[A-Za-z0-9_]+$", options: .regularExpression) != nil else { return nil }
                var member = V2Member(username: name)
                member.avatarNormal = cell.descendants("img").first { $0.hasClass("avatar") }?.attributes["src"]
                member.avatarLarge = member.avatarNormal
                return member
            }
            let replies = cell.descendants("a").first { link in
                (link.attributes["class"] ?? "").split(separator: " ").contains { $0.hasPrefix("count_") }
            }.flatMap { Int($0.text) } ?? 0
            let touched = cell.descendants("span").compactMap { $0.attributes["title"] }
                .compactMap { formatter.date(from: $0) }.first.map { Int($0.timeIntervalSince1970) }
            parsed.append(V2Topic(id: id, title: link.text, content: nil, contentRendered: nil,
                url: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/t/\(id)",
                replies: replies, created: nil, lastTouched: touched, lastReplyBy: nil, node: node, member: author))
        }
        if parsed.isEmpty {
            let hasFollowingNavigation = main.descendants("a").contains {
                Self.localURL($0.attributes["href"], base: base)?.path == "/my/following"
            }
            let emptyMessages = ["还没有关注任何人", "尚未关注任何人", "还没有特别关注", "没有关注任何用户", "关注的人还没有发布", "No topics", "not following anyone"]
            guard hasFollowingNavigation, emptyMessages.contains(where: { main.text.localizedCaseInsensitiveContains($0) }) else {
                throw V2EXError.decoding("未识别关注列表，不能确定是否为空，请稍后重试")
            }
        }
        topics = parsed
        hasMore = main.descendants("a").contains { link in
            guard let url = Self.localURL(link.attributes["href"], base: base), url.path == "/my/following",
                  let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
            let items = components.queryItems ?? []
            return items.count == 1 && items[0].name == "p" && Int(items[0].value ?? "") == page + 1
        }
    }

    private static func localURL(_ value: String?, base: URL) -> URL? {
        guard let value, let url = URL(string: value, relativeTo: base)?.absoluteURL,
              ReplyPage.sameOrigin(url, base), url.user == nil, url.password == nil else { return nil }
        return url
    }
}

final class FollowingService {
    private let base: URL
    private let session: URLSession

    init(base: URL, configuration: URLSessionConfiguration = .ephemeral) {
        self.base = base
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        session = URLSession(configuration: configuration)
    }

    deinit { session.invalidateAndCancel() }

    func topics(page: Int, cookie: String) async throws -> FollowingPage {
        guard !cookie.isEmpty, cookie.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }) else { throw V2EXError.sessionExpired }
        guard page > 0, page < Int.max, let url = URL(string: "/my/following?p=\(page)", relativeTo: base)?.absoluteURL else {
            throw V2EXError.decoding("关注列表页码无效")
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpShouldHandleCookies = false
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        request.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15", forHTTPHeaderField: "User-Agent")
        for _ in 0..<3 {
            try Task.checkCancellation()
            let (data, response) = try await session.data(for: request, delegate: FollowingRedirectDelegate())
            guard let http = response as? HTTPURLResponse else { throw V2EXError.decoding("网页响应无效") }
            if http.statusCode == 401 || http.statusCode == 403 { throw V2EXError.sessionExpired }
            if http.statusCode == 200 {
                guard let html = String(data: data, encoding: .utf8) else { throw V2EXError.decoding("网页编码无效") }
                return try FollowingPage(html: html, base: base, page: page)
            }
            guard [301, 302, 303, 307, 308].contains(http.statusCode) else { throw V2EXError.badStatus(http.statusCode) }
            guard let location = http.value(forHTTPHeaderField: "Location"),
                  let target = URL(string: location, relativeTo: request.url)?.absoluteURL,
                  ReplyPage.sameOrigin(target, base), target.user == nil, target.password == nil else {
                throw V2EXError.decoding("关注列表返回了异常跳转")
            }
            if ["/signin", "/2fa"].contains(target.path) { throw V2EXError.sessionExpired }
            guard target.path == "/my/following", target.fragment == nil,
                  target.query == "p=\(page)" || (page == 1 && target.query == nil) else {
                throw V2EXError.decoding("关注列表返回了异常跳转")
            }
            request.url = target
        }
        throw V2EXError.decoding("关注列表跳转过多")
    }
}

private final class FollowingRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) -> Void {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) -> Void {
        completionHandler(challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust
            ? .performDefaultHandling : .cancelAuthenticationChallenge, nil)
    }
}
