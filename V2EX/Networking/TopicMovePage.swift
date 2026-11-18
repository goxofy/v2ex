import Foundation

struct MoveNode: Codable, Equatable, Identifiable {
    let name: String
    let title: String
    var aliases: [String] = []
    var id: String { name }

    func matches(_ query: String) -> Bool {
        ([name, title] + aliases).contains { $0.localizedCaseInsensitiveContains(query.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
}

struct MoveTopic {
    let node: MoveNode
    let canMove: Bool
}

struct MoveForm {
    let shortcuts: [MoveNode]
    let hidden: [String: String]
}

enum MoveFailure: Error, LocalizedError {
    case unavailable, session, denied, changed, rejected, unconfirmed

    var errorDescription: String? {
        switch self {
        case .unavailable: return "无法读取移动权限或表单，请到网页核查"
        case .session: return "网页会话已变化或失效，请重新打开话题"
        case .denied: return "当前账号没有此话题的移动权限，或权限已撤销"
        case .changed: return "话题节点已变化，请核对当前节点并重新确认"
        case .rejected: return "服务器未接受移动，请到网页核查"
        case .unconfirmed: return "移动结果待确认，请先到网页核查，避免重复操作"
        }
    }
}

/// A deliberately small, fail-closed tree for site-owned controls, never a general HTML renderer.
struct TopicMovePage {
    private let root: MoveElement
    private let base: URL

    init(html: String, base: URL) {
        self.base = base
        root = MoveElement.parse(html)
    }

    func topic(id: Int) throws -> MoveTopic {
        try checkPage()
        let headers = mainBoxes.flatMap { $0.children }.filter { $0.hasClass("header") && $0.descendants("h1").count == 1 }
        guard headers.count == 1, let header = headers.first else { throw MoveFailure.unavailable }
        let nodes = header.descendants("a").compactMap { link -> MoveNode? in
            guard let url = resolved(link.attributes["href"]), url.query == nil, url.fragment == nil,
                  url.path.hasPrefix("/go/") else { return nil }
            let slug = String(url.path.dropFirst(4))
            guard Self.validSlug(slug) else { return nil }
            return MoveNode(name: slug, title: link.text.isEmpty ? slug : link.text)
        }
        let names = Set(nodes.map(\.name))
        guard names.count == 1, let node = nodes.first else { throw MoveFailure.unavailable }
        let links = header.descendants("a").filter { link in
            guard link.hasClass("op"), let url = resolved(link.attributes["href"]) else { return false }
            return url.path == "/move/topic/\(id)" && url.query == nil && url.fragment == nil
        }
        return MoveTopic(node: node, canMove: links.count == 1)
    }

    func form(id: Int) throws -> MoveForm {
        try checkPage()
        let candidates = mainBoxes.flatMap { box in
            box.children.filter { $0.hasClass("inner") }.flatMap { $0.descendants("form") }.map { (box, $0) }
        }.filter { !$0.1.descendants("select").filter { $0.attributes["name"] == "destination" }.isEmpty }
        guard candidates.count == 1, let (box, form) = candidates.first else { throw MoveFailure.denied }
        guard form.attributes["method"]?.lowercased() == "post",
              let action = resolved(form.attributes["action"]), action.path == "/move/topic/\(id)",
              action.query == nil, action.fragment == nil else { throw MoveFailure.unavailable }
        let controls = form.descendants().filter { ["input", "select", "textarea", "button"].contains($0.tag) && $0.attributes["name"] != nil }
        guard controls.filter({ $0.attributes["name"] == "destination" && $0.tag == "select" }).count == 1,
              controls.filter({ $0.attributes["name"] == "memo" && $0.tag == "input" }).count == 1 else { throw MoveFailure.unavailable }
        var hidden: [String: String] = [:]
        for field in controls {
            guard let name = field.attributes["name"] else { continue }
            if name == "destination", field.tag == "select" { continue }
            if name == "memo", field.tag == "input", ["", "text"].contains(field.attributes["type"]?.lowercased() ?? "") { continue }
            guard field.attributes["type"]?.lowercased() == "hidden",
                  ["once", "csrf", "csrf_token", "_csrf", "_xsrf"].contains(name), hidden[name] == nil,
                  let value = field.attributes["value"], !value.isEmpty else { throw MoveFailure.unavailable }
            hidden[name] = value
        }
        let regex = try NSRegularExpression(pattern: #"^javascript:chooseNode\(['"]([A-Za-z0-9_]+)['"]\);?$"#)
        var seen: Set<String> = []
        let shortcuts = box.descendants("a").compactMap { link -> MoveNode? in
            guard link.hasClass("node-xl"), let href = link.attributes["href"],
                  let match = regex.firstMatch(in: href, range: NSRange(href.startIndex..., in: href)) else { return nil }
            let slug = (href as NSString).substring(with: match.range(at: 1))
            guard seen.insert(slug).inserted else { return nil }
            return MoveNode(name: slug, title: link.text.isEmpty ? slug : link.text)
        }
        return MoveForm(shortcuts: shortcuts, hidden: hidden)
    }

    func checkPage() throws {
        let all = root.descendants()
        if all.contains(where: { $0.tag == "input" && $0.attributes["type"]?.lowercased() == "password" }) ||
            all.contains(where: { $0.tag == "form" && ["/signin", "/2fa"].contains($0.attributes["action"] ?? "") }) {
            throw MoveFailure.session
        }
        if all.contains(where: { $0.hasClass("problem") || $0.attributes["id"] == "problem" }) { throw MoveFailure.rejected }
        let title = root.descendants("title").first?.text.lowercased() ?? ""
        if title.contains("just a moment") || title.contains("attention required") { throw MoveFailure.unavailable }
    }

    static func validSlug(_ value: String) -> Bool {
        !value.isEmpty && value.range(of: #"^[A-Za-z0-9_]+$"#, options: .regularExpression) != nil
    }

    private var mainBoxes: [MoveElement] {
        let mains = root.descendants().filter { $0.attributes["id"] == "Main" }
        guard mains.count == 1 else { return [] }
        return mains[0].children.filter { $0.tag == "div" && $0.hasClass("box") }
    }

    private func resolved(_ href: String?) -> URL? {
        guard let href, let url = URL(string: href, relativeTo: base)?.absoluteURL,
              ReplyPage.sameOrigin(url, base), url.user == nil, url.password == nil else { return nil }
        return url
    }
}

private final class MoveElement {
    let tag: String
    let attributes: [String: String]
    var children: [MoveElement] = []
    var fragments: [String] = []
    var text: String { HTMLText.decode(fragments.joined()).trimmingCharacters(in: .whitespacesAndNewlines) }

    init(tag: String, attributes: [String: String] = [:]) {
        self.tag = tag
        self.attributes = attributes
    }

    func hasClass(_ value: String) -> Bool { attributes["class"]?.split(whereSeparator: \.isWhitespace).contains(Substring(value)) == true }

    func descendants(_ name: String? = nil) -> [MoveElement] {
        children.flatMap { child in (name == nil || child.tag == name ? [child] : []) + child.descendants(name) }
    }

    static func parse(_ html: String) -> MoveElement {
        let root = MoveElement(tag: "root")
        let source = html
            .replacingOccurrences(of: #"(?is)<!--[\s\S]*?-->|<(script|style|template|noscript)\b[^>]*>[\s\S]*?</\1\s*>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"(?is)(<textarea\b[^>]*>)[\s\S]*?(</textarea\s*>)"#, with: "$1$2", options: .regularExpression) as NSString
        guard let tags = try? NSRegularExpression(pattern: #"<(/?)([A-Za-z][A-Za-z0-9]*)\b((?:[^>"']|"[^"]*"|'[^']*')*)>"#),
              let attrs = try? NSRegularExpression(pattern: #"(?:^|\s)([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"#) else { return root }
        var stack = [root]
        var cursor = 0
        for match in tags.matches(in: source as String, range: NSRange(location: 0, length: source.length)) {
            let fragment = source.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            stack.forEach { $0.fragments.append(fragment) }
            cursor = NSMaxRange(match.range)
            let tag = source.substring(with: match.range(at: 2)).lowercased()
            if match.range(at: 1).length > 0 {
                if let index = stack.lastIndex(where: { $0.tag == tag }), index > 0 { stack.removeSubrange(index...) }
                continue
            }
            let raw = source.substring(with: match.range(at: 3)) as NSString
            var values: [String: String] = [:]
            for attr in attrs.matches(in: raw as String, range: NSRange(location: 0, length: raw.length)) {
                guard let value = (2...4).first(where: { attr.range(at: $0).location != NSNotFound }) else { continue }
                let key = raw.substring(with: attr.range(at: 1)).lowercased()
                if values[key] != nil { return MoveElement(tag: "invalid") }
                values[key] = HTMLText.decode(raw.substring(with: attr.range(at: value)))
            }
            let node = MoveElement(tag: tag, attributes: values)
            // Keep excluded subtrees out of the trusted tree, including any forged site controls.
            if !["topic_content", "reply_content", "topic_append", "subtle"].contains(where: node.hasClass) {
                stack.last?.children.append(node)
            }
            if !["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"].contains(tag) {
                stack.append(node)
            }
        }
        return root
    }
}
