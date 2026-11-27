import SwiftUI

@MainActor
final class ImageEditorSelection: ObservableObject {
    @Published var value: TextSelection?
    private var lastRange: NSRange?

    func record(in text: String) -> Void {
        guard let value else { return } // Presenting a picker must not erase the caret.
        let range: Range<String.Index>?
        switch value.indices {
        case .selection(let selection): range = selection
        case .multiSelection(let selections): range = selections.ranges.first
        @unknown default: range = nil
        }
        guard let range,
              range.lowerBound <= range.upperBound,
              (range.lowerBound == text.endIndex || text.indices.contains(range.lowerBound)),
              (range.upperBound == text.endIndex || text.indices.contains(range.upperBound)) else { return }
        lastRange = NSRange(range, in: text)
    }

    func insert(_ url: URL, into text: Binding<String>) -> Void {
        let result = ImageTextInsertion.insert(url: url, into: text.wrappedValue, selection: lastRange)
        text.wrappedValue = result.text
        lastRange = result.selection
        if let range = Range(result.selection, in: result.text) {
            value = TextSelection(range: range)
        }
    }

    func reset() -> Void {
        value = nil
        lastRange = nil
    }
}
