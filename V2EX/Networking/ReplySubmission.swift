import Foundation

/// 网页回帖没有写入 API：只读取回复表单和回复记录，不把草稿回显当作成功。
struct ReplyPage {
    struct Form {
        let url: URL
        let once: String
    }

    struct Record {
        let id: Int
        let author: String
        let text: String
    }

    private let html: String
    private let divs: [Element]

    private var contentRanges: [NSRange] {
        divs.filter { $0.hasClass("reply_content") || $0.hasClass("topic_content") }.map(\.range)
    }

    init(html: String) {
        // 这些区域里的标签／文字不是站点的操作按钮或回复记录。
        let markup = html
            .replacingOccurrences(of: #"(?is)<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?</script\s*>|<style\b[^>]*>[\s\S]*?</style\s*>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"(?is)(<textarea\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>)[\s\S]*?</textarea\s*>"#, with: "$1</textarea>", options: .regularExpression)
        self.html = markup
        divs = Self.elements("div", in: markup)
    }

    func form(topicURL: URL) throws -> Form {
        let forms = Self.elements("form", in: html).filter { form in
            Self.elements("textarea", in: form.inner).contains { $0.attributes["name"] == "content" }
        }
        let action = forms.first?.attributes["action"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let target = action.isEmpty ? topicURL : URL(string: action, relativeTo: topicURL)?.absoluteURL
        guard forms.count == 1, let form = forms.first,
              form.attributes["method"]?.lowercased() == "post",
              let target, Self.isTopicURL(target, relativeTo: topicURL) else {
            throw V2EXError.replyFailed("无法读取回复表单，帖子可能已关闭回复或网页结构已变化，请到网页查看")
        }
        let tokens = Self.elements("input", in: form.inner).filter { $0.attributes["name"] == "once" }
        guard tokens.count == 1, let once = tokens.first?.attributes["value"], !once.isEmpty else {
            throw V2EXError.replyFailed("无法读取回复表单的验证信息，请刷新话题或到网页回复")
        }
        return Form(url: target, once: once)
    }

    /// 获取提交前的最后一页，避免把旧的末页回复误认为本次新增。
    func lastPageURL(topicURL: URL) -> URL? {
        let current = Self.pageNumber(topicURL)
        let contentRanges = self.contentRanges
        return Self.elements("a", in: html).compactMap { link -> URL? in
            guard !contentRanges.contains(where: { NSLocationInRange(link.range.location, $0) }),
                  let href = link.attributes["href"],
                  let url = URL(string: href, relativeTo: topicURL)?.absoluteURL,
                  Self.isTopicURL(url, relativeTo: topicURL), Self.pageNumber(url) > current else { return nil }
            return url
        }.max { Self.pageNumber($0) < Self.pageNumber($1) }
    }

    var records: [Record] {
        let contentRanges = self.contentRanges
        return divs.compactMap { div -> Record? in
            guard !contentRanges.contains(where: { NSLocationInRange(div.range.location, $0) }),
                  let rawID = div.attributes["id"], rawID.hasPrefix("r_"),
                  let id = Int(rawID.dropFirst(2)), id > 0,
                  let body = Self.elements("div", in: div.inner).first(where: { $0.hasClass("reply_content") }),
                  let author = Self.elements("a", in: div.inner).compactMap({ link -> String? in
                      guard link.range.location < body.range.location,
                            let href = link.attributes["href"], href.hasPrefix("/member/"),
                            let url = URL(string: href), url.path.split(separator: "/").count == 2 else { return nil }
                      return String(url.path.dropFirst("/member/".count))
                  }).first else { return nil }
            let text = body.inner.replacingOccurrences(of: #"(?i)<br\b[^>]*>|</(?:p|div|li)>"#, with: " ", options: .regularExpression)
            return Record(id: id, author: author, text: Self.normalized(HTMLText.plain(text)))
        }
    }

    func confirms(content: String, username: String, after baseline: ReplyPage) -> Bool {
        guard !username.isEmpty else { return false }
        let lastID = baseline.divs.compactMap { div -> Int? in
            guard let id = div.attributes["id"], id.hasPrefix("r_") else { return nil }
            return Int(id.dropFirst(2))
        }.max() ?? 0
        return records.contains {
            $0.id > lastID && $0.author.caseInsensitiveCompare(username) == .orderedSame
                && $0.text == Self.normalized(content)
        }
    }

    var rejection: V2EXError? {
        // 只检查站点提示；回复／主题里讨论“验证码”或引用错误信息不能阻止回帖。
        let contentRanges = self.contentRanges
        let notices = divs.filter { element in
            !contentRanges.contains { NSLocationInRange(element.range.location, $0) }
        }
        if let problem = notices.first(where: { $0.attributes["id"] == "problem" || $0.hasClass("problem") }) {
            let message = HTMLText.plain(problem.inner)
            return .replyFailed(message.isEmpty ? "服务器未接受回复，请到网页查看" : String(message.prefix(500)))
        }
        var noticesHTML = html as NSString
        // 从后往前删掉用户内容，保留站点的冷却／关闭回复提示。
        let outerRanges = contentRanges.filter { range in
            !contentRanges.contains { $0 != range && NSLocationInRange(range.location, $0) }
        }
        for range in outerRanges.sorted(by: { $0.location > $1.location }) {
            noticesHTML = noticesHTML.replacingCharacters(in: range, with: "") as NSString
        }
        let text = HTMLText.plain(noticesHTML as String)
        if text.contains("你上一次回复是在") || text.contains("回复过于频繁") {
            return .replyFailed("回复过于频繁，V2EX 有回复间隔限制，请稍后再试")
        }
        if text.contains("回复功能已关闭") || text.contains("主题已关闭") || text.contains("不再接受新回复") {
            return .replyFailed("此话题已关闭回复，请到网页查看")
        }
        let forms = Self.elements("form", in: noticesHTML as String)
        if forms.contains(where: { form in
            guard let action = form.attributes["action"], let url = URL(string: action) else { return false }
            return ["/signin", "/2fa"].contains(url.path)
        }) { return .sessionExpired }
        if Self.elements("input", in: noticesHTML as String).contains(where: { $0.attributes["type"]?.lowercased() == "password" })
            || Self.elements("a", in: noticesHTML as String).contains(where: { link in
                guard let href = link.attributes["href"], URL(string: href)?.path == "/signin" else { return false }
                return link.hasClass("top") || ["登录", "sign in"].contains(HTMLText.plain(link.inner).lowercased())
            }) { return .sessionExpired }
        let title = Self.elements("title", in: html).first.map { HTMLText.plain($0.inner).lowercased() } ?? ""
        if title.contains("just a moment") || title.contains("attention required")
            || (noticesHTML as String).contains("cf-chl-")
            || Self.elements("input", in: noticesHTML as String).contains(where: { $0.attributes["name"]?.lowercased().contains("captcha") == true })
            || Self.elements("img", in: noticesHTML as String).contains(where: { $0.attributes["src"]?.contains("/_captcha") == true }) {
            return .replyFailed("V2EX 要求网页验证，请在网页完成验证后再试")
        }
        return nil
    }

    static func formBody(content: String, once: String) -> Data {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "content", value: content), URLQueryItem(name: "once", value: once)]
        // URL query 允许裸 '+'，而 form 解码会把它当空格。
        return Data((components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
    }

    static func isTopicURL(_ url: URL, relativeTo topicURL: URL) -> Bool {
        sameOrigin(url, topicURL) && url.path == topicURL.path
    }

    static func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
        guard lhs.user == nil, lhs.password == nil,
              let scheme = lhs.scheme?.lowercased(), ["https", "http"].contains(scheme) else { return false }
        return scheme == rhs.scheme?.lowercased() && lhs.host?.lowercased() == rhs.host?.lowercased()
            && (lhs.port ?? (scheme == "https" ? 443 : 80)) == (rhs.port ?? (scheme == "https" ? 443 : 80))
    }

    private static func pageNumber(_ url: URL) -> Int {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "p" }).flatMap { Int($0.value ?? "") } ?? 1
    }

    private static func normalized(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private struct Element {
        let attributes: [String: String]
        let inner: String
        let range: NSRange

        func hasClass(_ name: String) -> Bool {
            attributes["class"]?.split(whereSeparator: \.isWhitespace).contains(Substring(name)) == true
        }
    }

    /// 配对同名嵌套标签，避免 problem / reply_content 内的 div 截断内容。
    private static func elements(_ name: String, in html: String) -> [Element] {
        guard let regex = try? NSRegularExpression(pattern: "<(/?)" + name + #"\b((?:[^>"']|"[^"]*"|'[^']*')*)>"#, options: .caseInsensitive) else { return [] }
        let source = html as NSString
        var stack: [NSTextCheckingResult] = []
        var result: [Element] = []
        for tag in regex.matches(in: html, range: NSRange(location: 0, length: source.length)) {
            if tag.range(at: 1).length > 0 {
                guard let open = stack.popLast() else { continue }
                let start = NSMaxRange(open.range)
                result.append(Element(attributes: attributes(source.substring(with: open.range(at: 2))),
                                      inner: source.substring(with: NSRange(location: start, length: tag.range.location - start)),
                                      range: NSRange(location: open.range.location, length: NSMaxRange(tag.range) - open.range.location)))
            } else if ["input", "img"].contains(name) {
                result.append(Element(attributes: attributes(source.substring(with: tag.range(at: 2))), inner: "", range: tag.range))
            } else {
                stack.append(tag)
            }
        }
        return result.sorted { $0.range.location < $1.range.location }
    }

    private static func attributes(_ text: String) -> [String: String] {
        guard let regex = try? NSRegularExpression(pattern: #"(?:^|\s)([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"#) else { return [:] }
        let source = text as NSString
        var values: [String: String] = [:]
        for match in regex.matches(in: text, range: NSRange(location: 0, length: source.length)) {
            guard let value = (2...4).first(where: { match.range(at: $0).location != NSNotFound }) else { continue }
            let key = source.substring(with: match.range(at: 1)).lowercased()
            values[key] = HTMLText.decode(source.substring(with: match.range(at: value)))
        }
        return values
    }
}
