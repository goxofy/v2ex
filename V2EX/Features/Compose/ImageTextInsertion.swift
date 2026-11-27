import Foundation

/// UTF-16 matches both native text selection APIs; boundaries are clamped to
/// whole characters so stale selections cannot split an emoji or trap.
enum ImageTextInsertion {
    struct Result: Equatable {
        let text: String
        let selection: NSRange
    }

    static func insert(url: URL, into text: String, selection: NSRange?) -> Result {
        let range = normalized(selection, in: text)
        let stringRange = Range(range, in: text) ?? text.endIndex..<text.endIndex
        let insertion = "\n\(url.absoluteString)\n"
        var updated = text
        updated.replaceSubrange(stringRange, with: insertion)
        return Result(text: updated, selection: NSRange(location: range.location + insertion.utf16.count, length: 0))
    }

    static func normalized(_ selection: NSRange?, in text: String) -> NSRange {
        let length = text.utf16.count
        let requested = selection ?? NSRange(location: length, length: 0)
        let lower = min(max(0, requested.location), length)
        let upper = lower + min(max(0, requested.length), length - lower)
        var boundaries = [0]
        var offset = 0
        for character in text {
            offset += String(character).utf16.count
            boundaries.append(offset)
        }
        let start = boundaries.last(where: { $0 <= lower }) ?? 0
        let end = requested.length == 0 ? start : (boundaries.first(where: { $0 >= upper }) ?? length)
        return NSRange(location: start, length: end - start)
    }
}
