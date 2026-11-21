import Foundation
import Combine

enum HomeFeed: Hashable, Codable, Identifiable {
    case all, following, hot, r2, hackerNews
    case node(name: String, title: String)

    var id: String {
        switch self {
        case .all: return "all"
        case .following: return "following"
        case .hot: return "hot"
        case .r2: return "r2"
        case .hackerNews: return "hn"
        case .node(let name, _): return "node:\(name)"
        }
    }

    var title: String {
        switch self {
        case .all: return "全部"
        case .following: return "关注"
        case .hot: return "最热"
        case .r2: return "R2"
        case .hackerNews: return "HN"
        case .node(_, let title): return title
        }
    }
}

@MainActor
final class HomeTabsStore: ObservableObject {
    static let limit = 15
    static let builtins: [HomeFeed] = [.all, .hot, .r2, .following]
    @Published private(set) var items: [HomeFeed]
    private let preferences: UserDefaults
    private let key = "homeTabs.v1"

    init(preferences: UserDefaults = .standard) {
        self.preferences = preferences
        if let data = preferences.data(forKey: key), let decoded = try? JSONDecoder().decode([HomeFeed].self, from: data) {
            items = Self.normalize(decoded)
        } else {
            let nodes = preferences.stringArray(forKey: "followedNodes") ?? ["programmer", "create", "apple", "coffee", "autistic"]
            items = Self.defaults(nodes: nodes, hackerNews: preferences.bool(forKey: "hackerNewsEnabled"))
        }
        persist()
    }

    static func defaults(nodes: [String], hackerNews: Bool) -> [HomeFeed] {
        normalize(builtins + nodes.prefix(8).map { .node(name: $0, title: NodeCatalog.displayName(for: $0)) } + (hackerNews ? [.hackerNews] : []))
    }

    static func normalize(_ values: [HomeFeed]) -> [HomeFeed] {
        var seen: Set<String> = []
        let valid = values.filter { value in
            if case .node(let name, let title) = value {
                guard name.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil,
                      !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
            }
            return seen.insert(value.id).inserted
        }
        return valid.isEmpty ? [.all] : Array(valid.prefix(limit))
    }

    func visible(hackerNews: Bool) -> [HomeFeed] {
        let result = items.filter { hackerNews || $0 != .hackerNews }
        return result.isEmpty ? [.all] : result
    }

    func save(_ values: [HomeFeed]) -> Void {
        items = Self.normalize(values)
        persist()
    }

    private func persist() -> Void {
        guard let data = try? JSONEncoder().encode(items) else { return }
        preferences.set(data, forKey: key)
    }
}
