import XCTest

final class ProbeUITests: XCTestCase {
    @MainActor private func checkInsertion(editor: Bool) -> Void {
        let app = XCUIApplication()
        app.launchArguments = editor ? ["--editor"] : []
        app.launch()
        XCTAssertTrue(app.buttons["middle"].waitForExistence(timeout: 8))
        app.buttons["middle"].tap()
        app.buttons["mock"].tap()
        XCTAssertEqual(app.staticTexts["state"].label, "uploading")
        app.typeText("new")
        app.buttons["complete"].tap()
        let expected = "abnew\nhttps://i.imgur.com/test.jpeg\n😀cd"
        let predicate = NSPredicate(format: "label == %@", expected)
        expectation(for: predicate, evaluatedWith: app.staticTexts["result"])
        waitForExpectations(timeout: 5)
        app.typeText("Z")
        XCTAssertEqual(app.staticTexts["result"].label, "abnew\nhttps://i.imgur.com/test.jpeg\nZ😀cd")
    }
    @MainActor func testReplyCaretAndConcurrentEditing() -> Void { checkInsertion(editor: false) }
    @MainActor func testPostCaretAndConcurrentEditing() -> Void { checkInsertion(editor: true) }
    @MainActor func testPhotoPickerCancellationPreservesContextAndCaret() -> Void {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["middle"].waitForExistence(timeout: 8))
        app.buttons["middle"].tap()
        app.buttons["picker"].tap()
        app.buttons["选择图片并上传"].tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 8))
        cancel.tap()
        XCTAssertEqual(app.staticTexts["context"].label, "probe")
        XCTAssertEqual(app.staticTexts["result"].label, "ab😀cd")
        app.buttons["mock"].tap()
        app.buttons["complete"].tap()
        expectation(for: NSPredicate(format: "label == %@", "ab\nhttps://i.imgur.com/test.jpeg\n😀cd"), evaluatedWith: app.staticTexts["result"])
        waitForExpectations(timeout: 5)
    }
}
