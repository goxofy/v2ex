import Foundation

/// Blocks a rendered topic or reply body is broken into for display.
enum ContentBlock: Identifiable, Hashable {
    case paragraph(AttributedString)
    case code(String)
    case quote(AttributedString)
    case list([AttributedString])
    case quoteContent([ContentBlock])
    case listContent([[ContentBlock]])
    case image(URL)
    case rule

    var id: String {
        switch self {
        case .paragraph(let s): return "p:\(s.description.hashValue)"
        case .code(let s): return "c:\(s.hashValue)"
        case .quote(let s): return "q:\(s.description.hashValue)"
        case .list(let items): return "l:\(items.map(\.description).joined().hashValue)"
        case .quoteContent(let blocks): return "qc:\(blocks.hashValue)"
        case .listContent(let items): return "lc:\(items.hashValue)"
        case .image(let url): return "i:\(url.absoluteString)"
        case .rule: return "hr"
        }
    }
}

/// Small purpose-built HTML reader for the subset V2EX emits: `<p> <br> <a>
/// <code> <pre> <blockquote> <img> <ul>/<ol>/<li> <strong> <em>`. Purpose-built
/// rather than `NSAttributedString(documentType: .html)` because that variant
/// is main-thread-only and far too slow for a 250-reply topic.
enum HTMLText {

    // MARK: Public entry points

    /// Images keep their position even inside inline wrappers, quotes and lists.
    static func blocks(from html: String) -> [ContentBlock] {
        blockContent(html, depth: 0)
    }

    private struct InlineStyle {
        var intent: InlinePresentationIntent = []
        var link: URL?

        func apply(to text: String) -> AttributedString {
            var value = AttributedString(text)
            if !intent.isEmpty { value.inlinePresentationIntent = intent }
            value.link = link
            return value
        }
    }

    private static func blockContent(_ html: String, depth: Int) -> [ContentBlock] {
        guard depth < 64 else { return [.paragraph(AttributedString(plain(html)))] }
        var result: [ContentBlock] = []
        var pending = AttributedString()

        func flush() -> Void {
            result.append(contentsOf: imageLines(in: trimmed(pending)))
            pending = AttributedString()
        }

        func walk(_ fragment: String, style: InlineStyle, level: Int) -> Void {
            guard level < 64 else {
                pending += style.apply(to: plain(fragment))
                return
            }
            var scanner = Scanner(source: fragment)
            while let chunk = scanner.next() {
                switch chunk {
                case .text(let text):
                    pending += style.apply(to: decode(text))
                case .element(let name, let attributes, let inner):
                    switch name {
                    case "script", "style": break
                    case "img":
                        if let src = attributes["src"], let url = absoluteURL(src),
                           ["https", "http"].contains(url.scheme?.lowercased() ?? "") {
                            flush()
                            result.append(.image(url))
                        }
                    case "br": pending += style.apply(to: "\n")
                    case "hr":
                        flush()
                        result.append(.rule)
                    case "pre":
                        flush()
                        result.append(.code(decode(strip(inner)).trimmingCharacters(in: .newlines)))
                    case "code":
                        let text = decode(strip(inner))
                        if text.contains("\n") {
                            flush()
                            result.append(.code(text.trimmingCharacters(in: .newlines)))
                        } else {
                            var codeStyle = style
                            codeStyle.intent.insert(.code)
                            pending += codeStyle.apply(to: text)
                        }
                    case "blockquote":
                        flush()
                        let contents = blockContent(inner, depth: level + 1)
                        if contents.allSatisfy({ if case .paragraph = $0 { return true }; return false }) {
                            result.append(contentsOf: contents.map {
                                if case .paragraph(let text) = $0 { return .quote(text) }
                                return $0
                            })
                        } else if !contents.isEmpty {
                            result.append(.quoteContent(contents))
                        }
                    case "ul", "ol":
                        flush()
                        var items: [[ContentBlock]] = []
                        var itemScanner = Scanner(source: inner)
                        while let item = itemScanner.next() {
                            if case .element("li", _, let body) = item {
                                let contents = blockContent(body, depth: level + 1)
                                if !contents.isEmpty { items.append(contents) }
                            }
                        }
                        let textItems = items.compactMap { item -> AttributedString? in
                            guard item.count == 1, case .paragraph(let text) = item[0] else { return nil }
                            return text
                        }
                        if !items.isEmpty {
                            result.append(textItems.count == items.count ? .list(textItems) : .listContent(items))
                        }
                    case "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6":
                        flush()
                        var childStyle = style
                        if name.hasPrefix("h") { childStyle.intent.insert(.stronglyEmphasized) }
                        walk(inner, style: childStyle, level: level + 1)
                        flush()
                    default:
                        var childStyle = style
                        if name == "strong" || name == "b" { childStyle.intent.insert(.stronglyEmphasized) }
                        if name == "em" || name == "i" { childStyle.intent.insert(.emphasized) }
                        if name == "a" {
                            childStyle.link = linkTarget(href: attributes["href"], label: AttributedString(plain(inner)))
                        }
                        walk(inner, style: childStyle, level: level + 1)
                    }
                }
            }
        }

        walk(html, style: InlineStyle(), level: depth)
        flush()
        return result
    }

    /// Expand only a whole line containing a direct Imgur URL, never code or a
    /// descriptive/misdirected link. Preserve attributed text around each image.
    private static func imageLines(in text: AttributedString) -> [ContentBlock] {
        guard !text.characters.isEmpty else { return [] }
        var result: [ContentBlock] = []
        var pending = AttributedString()
        var start = text.startIndex

        func appendLine(endingAt end: AttributedString.Index, newline: Bool) -> Void {
            let line = AttributedString(text[start..<end])
            let candidate = trimmed(line)
            let raw = String(candidate.characters)
            let url = ImgurImageLink.url(from: raw)
            let allowed = candidate.runs.allSatisfy { run in
                run.inlinePresentationIntent?.contains(.code) != true
                    && (run.link == nil || run.link == url)
            }
            if let url, allowed {
                let before = trimmed(pending)
                if !before.characters.isEmpty { result.append(.paragraph(before)) }
                pending = AttributedString()
                result.append(.image(url))
            } else {
                pending += line
                if newline { pending += AttributedString("\n") }
            }
        }

        for index in text.characters.indices where text.characters[index] == "\n" {
            appendLine(endingAt: index, newline: true)
            start = text.index(afterCharacter: index)
        }
        appendLine(endingAt: text.endIndex, newline: false)
        let remainder = trimmed(pending)
        if !remainder.characters.isEmpty { result.append(.paragraph(remainder)) }
        return result
    }

    /// Inline-only parse: bold/italic/code/link runs, no block structure.
    /// Used for reply bodies and notification payloads.
    static func inline(_ html: String) -> AttributedString {
        var result = AttributedString()
        var scanner = Scanner(source: html)

        while let chunk = scanner.next() {
            switch chunk {
            case .text(let text):
                result += AttributedString(decode(text))
            case .element(let name, let attributes, let inner):
                var piece = inline(inner)
                switch name {
                case "strong", "b":
                    piece.inlinePresentationIntent = .stronglyEmphasized
                case "em", "i":
                    piece.inlinePresentationIntent = .emphasized
                case "code":
                    piece.inlinePresentationIntent = .code
                case "a":
                    if let url = linkTarget(href: attributes["href"], label: piece) {
                        piece.link = url
                    }
                case "br":
                    piece = AttributedString("\n")
                case "img":
                    if let src = attributes["src"], let url = absoluteURL(src) {
                        piece = AttributedString("[图片]")
                        piece.link = url
                    }
                default:
                    break
                }
                result += piece
            }
        }
        return trimmed(result)
    }

    /// Tags removed, entities decoded — for previews, search and notifications.
    static func plain(_ html: String) -> String {
        decode(strip(html))
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "  ", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Text of the first `<a>` whose href contains `matching` (used to pull the
    /// topic title out of a notification string).
    static func firstLinkText(in html: String, matching: String) -> String? {
        var scanner = Scanner(source: html)
        while let chunk = scanner.next() {
            if case .element(let name, let attributes, let inner) = chunk,
               name == "a", attributes["href"]?.contains(matching) == true {
                return plain(inner)
            }
        }
        return nil
    }

    static func firstTopicID(in html: String) -> Int? {
        var scanner = Scanner(source: html)
        while let chunk = scanner.next() {
            if case .element(let name, let attributes, _) = chunk,
               name == "a", let href = attributes["href"], href.contains("/t/") {
                let digits = href.drop { $0 != "/" }
                    .split(separator: "/")
                    .compactMap { Int($0.prefix { $0.isNumber }) }
                return digits.first
            }
        }
        return nil
    }

    // MARK: Internals

    private static func trimmed(_ value: AttributedString) -> AttributedString {
        var result = value
        while let first = result.characters.first, first.isWhitespace {
            result.removeSubrange(result.startIndex..<result.index(afterCharacter: result.startIndex))
        }
        while let last = result.characters.last, last.isWhitespace {
            let end = result.endIndex
            result.removeSubrange(result.index(beforeCharacter: end)..<end)
        }
        return result
    }

    /// An anchor's destination, falling back to its own label when the `href`
    /// is unusable.
    ///
    /// The markdown `[https://example.com]()` — URL in the label slot, target
    /// left empty — is a common enough slip that V2EX renders it verbatim as
    /// `<a href="">https://example.com</a>`. That leaves a full URL sitting on
    /// screen with nothing behind it. The fallback insists on an http(s) URL
    /// with a host so an ordinary text label like "点这里" stays plain text
    /// rather than becoming a link to nowhere.
    static func linkTarget(href: String?, label: AttributedString) -> URL? {
        if let href, let url = absoluteURL(href) { return url }

        let text = String(label.characters).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host?.isEmpty == false
        else { return nil }
        return url
    }

    static func absoluteURL(_ raw: String) -> URL? {
        var value = raw.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        if value.hasPrefix("//") { value = "https:" + value }
        else if value.hasPrefix("/") { value = V2EXEndpoint.officialBase + value }
        return URL(string: value)
    }

    static func strip(_ html: String) -> String {
        var output = ""
        var insideTag = false
        for character in html {
            switch character {
            case "<": insideTag = true
            case ">": insideTag = false
            default: if !insideTag { output.append(character) }
            }
        }
        return output
    }

    static func decode(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var output = text
        let entities = [
            "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"",
            "&#39;": "'", "&apos;": "'", "&nbsp;": " ", "&hellip;": "…",
            "&mdash;": "—", "&ndash;": "–", "&ldquo;": "\u{201C}", "&rdquo;": "\u{201D}",
        ]
        for (entity, replacement) in entities {
            output = output.replacingOccurrences(of: entity, with: replacement)
        }
        // Numeric character references, decimal (`&#39;`) and hex (`&#x27;`).
        // Hex used to be missed entirely, which is invisible on V2EX — it emits
        // decimal — but mangles every Hacker News body, since HN escapes with
        // hex throughout: `I&#x27;m`, `https:&#x2F;&#x2F;`.
        while let range = output.range(of: "&#[xX]?[0-9A-Fa-f]+;", options: .regularExpression) {
            var digits = output[range].dropFirst(2).dropLast()
            var radix = 10
            if digits.first == "x" || digits.first == "X" {
                digits = digits.dropFirst()
                radix = 16
            }
            guard let value = UInt32(digits, radix: radix), let scalar = Unicode.Scalar(value) else {
                output.replaceSubrange(range, with: "")
                continue
            }
            output.replaceSubrange(range, with: String(Character(scalar)))
        }
        return output
    }

    // MARK: Tokenizer

    private enum Chunk {
        case text(String)
        case element(name: String, attributes: [String: String], inner: String)
    }

    /// Walks the string once, pairing each opening tag with its matching close
    /// (nesting-aware) and returning the raw inner HTML for recursion.
    private struct Scanner {
        private let characters: [Character]
        private var index = 0

        init(source: String) {
            characters = Array(source)
        }

        mutating func next() -> Chunk? {
            guard index < characters.count else { return nil }

            if characters[index] != "<" {
                var text = ""
                while index < characters.count, characters[index] != "<" {
                    text.append(characters[index])
                    index += 1
                }
                return .text(text)
            }

            guard let tag = readTag() else {
                // A stray "<" that never closes — emit it as text.
                index += 1
                return .text("<")
            }

            if tag.isClosing {
                // Unbalanced closing tag; skip it.
                return next()
            }

            let voidElements: Set<String> = ["br", "img", "hr", "input", "meta", "link"]
            if tag.isSelfClosing || voidElements.contains(tag.name) {
                return .element(name: tag.name, attributes: tag.attributes, inner: "")
            }

            let inner = readInner(until: tag.name)
            return .element(name: tag.name, attributes: tag.attributes, inner: inner)
        }

        private struct Tag {
            let name: String
            let attributes: [String: String]
            let isClosing: Bool
            let isSelfClosing: Bool
        }

        /// Reads `<…>` starting at the current index, honouring quoted attributes.
        private mutating func readTag() -> Tag? {
            let start = index
            guard index < characters.count, characters[index] == "<" else { return nil }
            index += 1

            var raw = ""
            var quote: Character?
            while index < characters.count {
                let character = characters[index]
                if let active = quote {
                    if character == active { quote = nil }
                    raw.append(character)
                } else if character == "\"" || character == "'" {
                    quote = character
                    raw.append(character)
                } else if character == ">" {
                    index += 1
                    return parse(raw: raw)
                } else {
                    raw.append(character)
                }
                index += 1
            }
            index = start
            return nil
        }

        private func parse(raw: String) -> Tag {
            var body = raw
            let isClosing = body.hasPrefix("/")
            if isClosing { body.removeFirst() }
            let isSelfClosing = body.hasSuffix("/")
            if isSelfClosing { body.removeLast() }

            var name = ""
            var rest = Substring(body)
            while let character = rest.first, !character.isWhitespace {
                name.append(character)
                rest = rest.dropFirst()
            }

            return Tag(
                name: name.lowercased(),
                attributes: isClosing ? [:] : parseAttributes(String(rest)),
                isClosing: isClosing,
                isSelfClosing: isSelfClosing
            )
        }

        private func parseAttributes(_ source: String) -> [String: String] {
            var attributes: [String: String] = [:]
            var remainder = Substring(source)

            while !remainder.isEmpty {
                remainder = remainder.drop { $0.isWhitespace }
                guard !remainder.isEmpty else { break }

                var key = ""
                while let character = remainder.first, character != "=", !character.isWhitespace {
                    key.append(character)
                    remainder = remainder.dropFirst()
                }
                guard !key.isEmpty else { remainder = remainder.dropFirst(); continue }

                remainder = remainder.drop { $0.isWhitespace }
                guard remainder.first == "=" else {
                    attributes[key.lowercased()] = ""
                    continue
                }
                remainder = remainder.dropFirst().drop { $0.isWhitespace }

                var value = ""
                if let quote = remainder.first, quote == "\"" || quote == "'" {
                    remainder = remainder.dropFirst()
                    while let character = remainder.first, character != quote {
                        value.append(character)
                        remainder = remainder.dropFirst()
                    }
                    if !remainder.isEmpty { remainder = remainder.dropFirst() }
                } else {
                    while let character = remainder.first, !character.isWhitespace {
                        value.append(character)
                        remainder = remainder.dropFirst()
                    }
                }
                attributes[key.lowercased()] = HTMLText.decode(value)
            }
            return attributes
        }

        /// Consumes up to the matching `</name>`, tracking nested same-name tags.
        private mutating func readInner(until name: String) -> String {
            var depth = 1
            var inner = ""

            while index < characters.count {
                if characters[index] == "<" {
                    let checkpoint = index
                    if let tag = readTag() {
                        if tag.name == name && !tag.isSelfClosing {
                            if tag.isClosing {
                                depth -= 1
                                if depth == 0 { return inner }
                            } else {
                                depth += 1
                            }
                        }
                        inner += String(characters[checkpoint..<index])
                        continue
                    }
                    index = checkpoint
                }
                inner.append(characters[index])
                index += 1
            }
            return inner
        }
    }
}