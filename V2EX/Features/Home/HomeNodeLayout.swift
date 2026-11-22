import Foundation
import Observation

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

struct HomeNodeScrollSample: Equatable {
    let offset: CGFloat
    let topInset: CGFloat
    let viewportHeight: CGFloat
}

/// A feed's presentation state, observed only by transform/opacity modifiers.
@MainActor @Observable
final class HomeNodeScrollState {
    static let travel: CGFloat = 160
    static let snapDuration: TimeInterval = 0.12
    private(set) var progress: CGFloat = 0
    @ObservationIgnored private var previous: HomeNodeScrollSample?
    @ObservationIgnored private var snapTask: Task<Void, Never>?

    var snapTarget: CGFloat { progress < 0.5 ? 0 : 1 }

    func receive(_ sample: HomeNodeScrollSample) -> Void {
        guard sample.offset.isFinite, sample.topInset.isFinite,
              sample.viewportHeight.isFinite, sample.viewportHeight > 0 else { return }
        let old = previous
        previous = sample
        if sample.offset <= 0 {
            interrupt()
            progress = 0
            return
        }
        // Rotation/inset changes rebase the scroll origin, never move the header.
        guard let old, old.topInset == sample.topInset,
              old.viewportHeight == sample.viewportHeight else { return }
        let delta = sample.offset - max(0, old.offset)
        guard delta != 0 else { return }
        interrupt()
        // Clamp at either end so reverse scrolling reveals the header immediately,
        // even far down a feed. A snap never changes the list's content offset.
        progress = min(1, max(0, progress + delta / Self.travel))
    }

    func interrupt() -> Void {
        snapTask?.cancel()
        snapTask = nil
    }

    func settle(reduceMotion: Bool) -> Void {
        interrupt()
        let target = snapTarget
        guard progress != target else { return }
        guard !reduceMotion else { progress = target; return }
        let origin = progress
        snapTask = Task { @MainActor [weak self] in
            let clock = ContinuousClock()
            let start = clock.now
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(16)) } catch { return }
                guard !Task.isCancelled, let self else { return }
                let elapsed = start.duration(to: clock.now).components
                let seconds = Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
                let fraction = min(1, seconds / Self.snapDuration)
                let eased = CGFloat(1 - pow(1 - fraction, 3))
                self.progress = origin + (target - origin) * eased
                if fraction >= 1 { self.snapTask = nil; return }
            }
        }
    }

    deinit { snapTask?.cancel() }
}

/// Stable references avoid subscribing HomeView or its topic rows to scroll pixels.
@MainActor
final class HomeNodeScrollStates {
    private var states: [HomeFeed: HomeNodeScrollState] = [:]

    func state(for feed: HomeFeed) -> HomeNodeScrollState {
        if let state = states[feed] { return state }
        let state = HomeNodeScrollState()
        states[feed] = state
        return state
    }

    func interruptAll() -> Void {
        for state in states.values { state.interrupt() }
    }
}
