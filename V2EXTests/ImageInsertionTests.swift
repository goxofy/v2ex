import XCTest
@testable import V2EX

final class ImageInsertionTests: XCTestCase {
    private let url = URL(string: "https://i.imgur.com/v3lpwO1.jpeg")!

    func testStartMiddleEndAndSelectedText() -> Void {
        let insert = "\n\(url.absoluteString)\n"
        for (range, expected) in [
            (NSRange(location: 0, length: 0), insert + "前后"),
            (NSRange(location: 1, length: 0), "前" + insert + "后"),
            (NSRange(location: 2, length: 0), "前后" + insert),
            (NSRange(location: 0, length: 1), insert + "后")
        ] {
            let result = ImageTextInsertion.insert(url: url, into: "前后", selection: range)
            XCTAssertEqual(result.text, expected)
            XCTAssertEqual(result.selection, NSRange(location: range.location + insert.utf16.count, length: 0))
        }
    }

    func testEmptyDraftInitialCaretAndExistingNewlines() -> Void {
        let insert = "\n\(url.absoluteString)\n"
        XCTAssertEqual(ImageTextInsertion.insert(url: url, into: "", selection: nil).text, insert)
        XCTAssertEqual(ImageTextInsertion.insert(url: url, into: "正文", selection: nil).text, "正文" + insert)
        XCTAssertEqual(ImageTextInsertion.insert(url: url, into: "前\n后", selection: NSRange(location: 2, length: 0)).text, "前\n" + insert + "后")
    }

    func testEmojiAndStaleSelectionsNeverSplitCharacters() -> Void {
        let text = "中👨‍👩‍👧‍👦🙂文"
        for index in 0...text.utf16.count {
            let result = ImageTextInsertion.insert(url: url, into: text, selection: NSRange(location: index, length: 0))
            XCTAssertEqual(result.text.replacingOccurrences(of: "\n\(url.absoluteString)\n", with: ""), text)
            XCTAssertNotNil(Range(result.selection, in: result.text))
        }
        let stale = ImageTextInsertion.insert(url: url, into: "新文", selection: NSRange(location: Int.max, length: Int.max))
        XCTAssertEqual(stale.text, "新文\n\(url.absoluteString)\n")
        let selected = ImageTextInsertion.insert(url: url, into: "A🙂B", selection: NSRange(location: 2, length: 1))
        XCTAssertEqual(selected.text, "A\n\(url.absoluteString)\nB")
    }

    func testRepeatedInsertionsUseUpdatedTextAndCaret() -> Void {
        let first = ImageTextInsertion.insert(url: url, into: "前后", selection: NSRange(location: 1, length: 0))
        let second = ImageTextInsertion.insert(url: url, into: first.text, selection: first.selection)
        XCTAssertEqual(second.text, "前\n\(url.absoluteString)\n\n\(url.absoluteString)\n后")
    }
}

@MainActor
final class ImageUploadLifecycleTests: XCTestCase {
    private let url = URL(string: "https://i.imgur.com/v3lpwO1.jpeg")!
    private let image = UploadImage(data: Data([1]), encoding: .jpeg)

    func testCompletionUsesLatestDraftAndSelection() async -> Void {
        let started = expectation(description: "Upload started")
        let client = DeferredImageClient(started: started)
        let coordinator = ImageUploadCoordinator(client: client)
        coordinator.setContext("draft-A")
        var text = "旧正文"
        var selection = NSRange(location: 0, length: 0)
        coordinator.start(context: "draft-A", clientID: "client123", prepare: { self.image }) { url in
            text = ImageTextInsertion.insert(url: url, into: text, selection: selection).text
        }
        let task = coordinator.task
        await fulfillment(of: [started], timeout: 2)
        text = "新增🙂正文"
        selection = NSRange(location: 4, length: 0)
        await client.finish(url)
        await task?.value
        XCTAssertEqual(text, "新增🙂\n\(url.absoluteString)\n正文")
        XCTAssertFalse(coordinator.isUploading)
    }

    func testSwitchingDraftAndReturningRejectsLateResult() async -> Void {
        let started = expectation(description: "Upload started")
        let client = DeferredImageClient(started: started)
        let coordinator = ImageUploadCoordinator(client: client)
        coordinator.setContext("draft-A")
        var inserted = false
        coordinator.start(context: "draft-A", clientID: "client123", prepare: { self.image }) { _ in inserted = true }
        let oldTask = coordinator.task
        await fulfillment(of: [started], timeout: 2)
        coordinator.setContext("draft-B")
        coordinator.setContext("draft-A")
        await client.finish(url) // Deliberately ignores cancellation like a late provider callback.
        await oldTask?.value
        XCTAssertFalse(inserted)
        XCTAssertFalse(coordinator.isUploading)
        XCTAssertNil(coordinator.error)
    }

    func testDuplicateClickAndWrongContextDoNotPrepareAgain() async -> Void {
        let started = expectation(description: "Upload started")
        let client = DeferredImageClient(started: started)
        let coordinator = ImageUploadCoordinator(client: client)
        coordinator.setContext("reply-1")
        var preparations = 0
        var insertions = 0
        for context in ["reply-2", "reply-1", "reply-1"] {
            coordinator.start(context: context, clientID: "client123", prepare: {
                preparations += 1
                return self.image
            }) { _ in insertions += 1 }
        }
        let task = coordinator.task
        await fulfillment(of: [started], timeout: 2)
        await client.finish(url)
        await task?.value
        XCTAssertEqual(preparations, 1)
        XCTAssertEqual(insertions, 1)
    }

    func testPreparationFailureLeavesDraftAndClearsBusyState() async -> Void {
        let coordinator = ImageUploadCoordinator()
        coordinator.setContext("draft")
        var text = "保留正文"
        coordinator.start(context: "draft", clientID: "client123", prepare: {
            throw ImgurUploadError.unsupportedImage
        }) { _ in text = "wrong" }
        await coordinator.task?.value
        XCTAssertEqual(text, "保留正文")
        XCTAssertFalse(coordinator.isUploading)
        XCTAssertNotNil(coordinator.error)
    }
}

private actor DeferredImageClient: ImgurUploading {
    private let started: XCTestExpectation
    private var continuation: CheckedContinuation<URL, Error>?

    init(started: XCTestExpectation) { self.started = started }

    func upload(_ image: UploadImage, clientID: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            started.fulfill()
        }
    }

    func finish(_ url: URL) -> Void {
        continuation?.resume(returning: url)
        continuation = nil
    }
}
