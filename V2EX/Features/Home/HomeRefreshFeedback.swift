import Foundation

struct HomeRefreshFeedback: Equatable, Identifiable {
    let id = UUID()
    let feed: HomeFeed
    let title: String
    let icon: String
    let lastSuccess: Date?

    init?(feed: HomeFeed, previous: HomeViewModel.RefreshState?, current: HomeViewModel.RefreshState) {
        // SwiftUI can coalesce the loading transition for a fast response. A changed
        // success timestamp still signals completion; unchanged cached state does not.
        switch current.phase {
        case .success(let unchanged):
            guard let date = current.lastSuccess, date != previous?.lastSuccess else { return nil }
            title = unchanged ? "已获取，列表无变化" : "已获取列表"
            icon = "checkmark.circle"
        case .failed:
            guard previous?.phase != .failed else { return nil }
            title = "刷新失败，可在列表中重试"
            icon = "wifi.exclamationmark"
        case .cancelled:
            guard previous?.phase != .cancelled else { return nil }
            title = "刷新已取消，可在列表中重试"
            icon = "arrow.clockwise"
        case .idle, .refreshing:
            return nil
        }
        self.feed = feed
        lastSuccess = current.lastSuccess
    }

    static func retryMessage(error: String?, phase: HomeViewModel.RefreshState.Phase) -> String? {
        if let error, !error.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return error }
        switch phase {
        case .failed: return "刷新失败，请重试"
        case .cancelled: return "刷新已取消，请重试"
        case .idle, .refreshing, .success: return nil
        }
    }
}
