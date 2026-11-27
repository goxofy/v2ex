import XCTest
@testable import V2EX

final class ImageContentTests: XCTestCase {
    private let raw = "https://i.imgur.com/v3lpwO1.jpeg"

    func testImagesSurviveInlineWrappersAndKeepTextOrder() -> Void {
        for wrapper in ["span", "div", "strong", "em", "a href='/t/123'"] {
            let tag = wrapper.split(separator: " ")[0]
            let blocks = HTMLText.blocks(from: "<p>前<\(wrapper)>中<img src='\(raw)'>后</\(tag)>末</p>")
            XCTAssertEqual(images(blocks), [raw], wrapper)
            XCTAssertEqual(texts(blocks).joined(), "前中后末", wrapper)
            XCTAssertFalse(texts(blocks).joined().contains("[图片]"), wrapper)
        }
    }

    func testLinkedMultipleImagesUseSrcNotAnchorHref() -> Void {
        let other = "https://example.com/photo.png"
        let blocks = HTMLText.blocks(from: "<A HREF='https://imgur.com/gallery/album'>前<IMG SRC='\(raw)'><img src=\"\(other)\">后</A>")
        XCTAssertEqual(images(blocks), [raw, other])
        XCTAssertEqual(texts(blocks), ["前", "后"])
    }

    func testImagesStayInsideQuotesAndListItems() -> Void {
        let quote = HTMLText.blocks(from: "<blockquote><p>前<span><img src='\(raw)'></span>后</p></blockquote>")
        guard case .quoteContent(let contents) = quote.first else { return XCTFail("Missing rich quote") }
        XCTAssertEqual(images(contents), [raw])
        XCTAssertEqual(texts(contents), ["前", "后"])
        let list = HTMLText.blocks(from: "<ul><li>一<img src='\(raw)'>二</li><li>三</li></ul>")
        guard case .listContent(let items) = list.first else { return XCTFail("Missing rich list") }
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(images(items[0]), [raw])
        XCTAssertEqual(texts(items[0]), ["一", "二"])
        XCTAssertEqual(texts(items[1]), ["三"])
    }

    func testStandaloneRawURLsExpandWithoutDuplicatingServerImages() -> Void {
        for html in [raw, "<p>\(raw)</p>", "<a href='\(raw)'>\(raw)</a>", "<img src='\(raw)'>"] {
            XCTAssertEqual(HTMLText.blocks(from: html), [.image(URL(string: raw)!)], html)
        }
        let blocks = HTMLText.blocks(from: "前\n\(raw)\n后<br>\(raw)")
        XCTAssertEqual(images(blocks), [raw, raw])
        XCTAssertEqual(texts(blocks), ["前", "后"])
    }

    func testOrdinaryLinksCodeAndEscapedHTMLStayText() -> Void {
        for html in [
            "看看 \(raw)", "<a href='\(raw)'>查看图片</a>",
            "<a href='https://example.com/'>\(raw)</a>",
            "<code>\(raw)</code>", "<pre>\(raw)</pre>",
            "&lt;img src='\(raw)'&gt;", "https://imgur.com/gallery/album"
        ] {
            XCTAssertTrue(images(HTMLText.blocks(from: html)).isEmpty, html)
        }
    }

    func testTextFormattingAndMentionLinksSurviveImageSplits() -> Void {
        let blocks = HTMLText.blocks(from: "<strong>粗<img src='\(raw)'>体</strong> <a href='/member/alice'>@alice</a>")
        XCTAssertEqual(images(blocks), [raw])
        guard case .paragraph(let before) = blocks.first,
              case .paragraph(let after) = blocks.last else { return XCTFail("Missing text") }
        XCTAssertTrue(before.runs.allSatisfy { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true })
        XCTAssertEqual(String(after.characters), "体 @alice")
        XCTAssertEqual(after.runs.compactMap(\.link).last?.absoluteString, "https://www.v2ex.com/member/alice")
    }

    func testOnlySafeImgurOriginalURLsAreAccepted() -> Void {
        XCTAssertNotNil(ImgurImageLink.url(from: raw))
        for value in [
            "http://i.imgur.com/a.jpg", "https://imgur.com/a.jpg", "https://i.imgur.com.evil.test/a.jpg",
            "https://user@i.imgur.com/a.jpg", "https://i.imgur.com:8443/a.jpg",
            "https://i.imgur.com/a.jpg?x=1", "https://i.imgur.com/a.jpg#x",
            "https://i.imgur.com/a.html", "https://i.imgur.com/dir/a.jpg",
            "https://i.imgur.com/%61.jpg", "javascript:alert(1)"
        ] {
            XCTAssertNil(ImgurImageLink.url(from: value), value)
        }
        XCTAssertTrue(images(HTMLText.blocks(from: "<img src='javascript:alert(1)'>")).isEmpty)
    }

    private func images(_ blocks: [ContentBlock]) -> [String] {
        blocks.flatMap { block -> [String] in
            switch block {
            case .image(let url): return [url.absoluteString]
            case .quoteContent(let contents): return images(contents)
            case .listContent(let items): return items.flatMap(images)
            default: return []
            }
        }
    }

    private func texts(_ blocks: [ContentBlock]) -> [String] {
        blocks.flatMap { block -> [String] in
            switch block {
            case .paragraph(let text), .quote(let text): return [String(text.characters)]
            case .list(let items): return items.map { String($0.characters) }
            case .quoteContent(let contents): return texts(contents)
            case .listContent(let items): return items.flatMap(texts)
            default: return []
            }
        }
    }
}
