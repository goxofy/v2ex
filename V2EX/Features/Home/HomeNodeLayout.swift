import Foundation

/// Packs natural chip sizes in configuration order: left to right, then down.
/// If three rows do not fit the viewport, widen the scrollable canvas, never a chip.
final class HomeNodeLayout {
    let frames: [CGRect]
    let size: CGSize

    init(sizes: [CGSize], viewportWidth: CGFloat, maxRows: Int, columnSpacing: CGFloat = 6, rowSpacing: CGFloat = 2) {
        let sizes = sizes.map { CGSize(width: Self.valid($0.width), height: Self.valid($0.height)) }
        let gap = Self.valid(columnSpacing)
        let rowGap = Self.valid(rowSpacing)
        let rows = max(1, maxRows)
        let minimum = max(Self.valid(viewportWidth), sizes.map(\.width).max() ?? 0)
        let total = sizes.reduce(CGFloat.zero) { $0 + $1.width } + CGFloat(max(0, sizes.count - 1)) * gap
        var low = minimum
        var high = max(minimum, total)
        // A bounded search also works for fractional font metrics.
        for _ in 0..<32 {
            let width = (low + high) / 2
            if Self.rowCount(sizes: sizes, width: width, gap: gap) <= rows { high = width }
            else { low = width }
        }
        let width = ceil(high)
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        for size in sizes {
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + rowGap
                rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            x += size.width + gap
            rowHeight = max(rowHeight, size.height)
        }
        self.frames = frames
        size = CGSize(width: width, height: sizes.isEmpty ? 0 : y + rowHeight)
    }

    private static func valid(_ value: CGFloat) -> CGFloat {
        value.isFinite ? max(0, value) : 0
    }

    private static func rowCount(sizes: [CGSize], width: CGFloat, gap: CGFloat) -> Int {
        var rows = sizes.isEmpty ? 0 : 1
        var x: CGFloat = 0
        for size in sizes {
            if x > 0, x + size.width > width { rows += 1; x = 0 }
            x += size.width + gap
        }
        return rows
    }
}

/// One viewport's arrangements; height animation does not invalidate natural chip sizes.
struct HomeNodeLayoutCache {
    private(set) var sizes: [CGSize]
    private var viewportWidth: CGFloat?
    private var layouts: [Int: HomeNodeLayout] = [:]

    init(sizes: [CGSize]) {
        self.sizes = sizes
    }

    mutating func update(sizes: [CGSize]) -> Void {
        guard self.sizes != sizes else { return }
        self.sizes = sizes
        layouts.removeAll(keepingCapacity: true)
    }

    mutating func layout(viewportWidth: CGFloat, maxRows: Int) -> HomeNodeLayout {
        let width = viewportWidth.isFinite ? max(0, viewportWidth) : 0
        if self.viewportWidth != width {
            self.viewportWidth = width
            layouts.removeAll(keepingCapacity: true)
        }
        if let layout = layouts[maxRows] { return layout }
        let layout = HomeNodeLayout(sizes: sizes, viewportWidth: width, maxRows: maxRows)
        layouts[maxRows] = layout
        return layout
    }
}

/// Only the outer viewport changes height; both rails keep their full-size content.
struct HomeNodeTransition {
    let progress: CGFloat

    func height(expanded: CGFloat, collapsed: CGFloat) -> CGFloat {
        let fraction = progress.isFinite ? min(1, max(0, progress)) : 0
        return expanded + (collapsed - expanded) * fraction
    }
}

struct HomeNodeScrollSample: Equatable {
    let offset: CGFloat
    let topInset: CGFloat
    let viewportHeight: CGFloat

    func collapsed(after previous: Self, wasCollapsed: Bool) -> Bool {
        // Header animation changes the safe area/viewport, not the user's scroll.
        guard topInset == previous.topInset, viewportHeight == previous.viewportHeight,
              offset.isFinite else { return wasCollapsed }
        // The hysteresis gap exceeds the height released by two compact rows.
        return wasCollapsed ? offset > 8 : offset > 120
    }
}
