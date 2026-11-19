import SwiftUI

struct HomeTabsEditor: View {
    @ObservedObject var store: HomeTabsStore
    let followedNodes: [String]
    let hackerNews: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var draft: [HomeFeed] = []
    @State private var nodes: [V2Node] = []
    @State private var query = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("已选 \(draft.count)/15，包含全部、最热等标签。移除标签不会取消官网收藏。")
                        .font(.footnote).foregroundStyle(.secondary)
                    ForEach(draft) { feed in
                        Text(feed == .hackerNews && !hackerNews ? "HN（已在设置中隐藏）" : feed.title)
                    }
                        .onDelete { offsets in
                            guard draft.count > offsets.count else { return }
                            draft.remove(atOffsets: offsets)
                        }
                        .onMove { draft.move(fromOffsets: $0, toOffset: $1) }
                } header: { Text("首页标签（至少保留一项）") }
                Section("添加标签") {
                    ForEach((HomeTabsStore.builtins + (hackerNews ? [.hackerNews] : [])).filter { candidate in !draft.contains { $0.id == candidate.id } }) { feed in
                        Button { add(feed) } label: { Label(feed.title, systemImage: "plus.circle") }
                            .disabled(draft.count >= HomeTabsStore.limit)
                    }
                    TextField("搜索节点名称或英文标识", text: $query)
                    if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        let results = nodes.filter { node in
                            (node.name.localizedCaseInsensitiveContains(query) || node.title.localizedCaseInsensitiveContains(query)) && !draft.contains { $0.id == "node:\(node.name)" }
                        }
                        if results.isEmpty { Text("没有可添加的匹配节点").foregroundStyle(.secondary) }
                        ForEach(results) { node in
                            Button { add(.node(name: node.name, title: node.title)) } label: {
                                Label("\(node.title) / \(node.name)", systemImage: "plus.circle")
                            }.disabled(draft.count >= HomeTabsStore.limit)
                        }
                    }
                    if let error { Text(error).foregroundStyle(.secondary); Button("重试加载节点") { Task { await loadNodes() } } }
                    if draft.count >= HomeTabsStore.limit { Text("最多 15 项，请先移除一项再添加").foregroundStyle(.secondary) }
                }
                Section {
                    Button("恢复默认") { draft = HomeTabsStore.defaults(nodes: followedNodes, hackerNews: hackerNews) }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("编辑首页标签")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") { store.save(draft); dismiss() } }
            }
            .onAppear { if draft.isEmpty { draft = store.items } }
            .task { await loadNodes() }
        }
    }

    private func add(_ feed: HomeFeed) -> Void {
        guard draft.count < HomeTabsStore.limit, !draft.contains(where: { $0.id == feed.id }) else { return }
        draft.append(feed)
    }

    private func loadNodes() async -> Void {
        do {
            let fetched = try await V2EXClient.shared.allNodes()
            var seen: Set<String> = []
            nodes = fetched.filter { seen.insert($0.name).inserted }
            error = nil
        } catch {
            guard !error.isCancellation else { return }
            self.error = "节点目录暂时无法加载，已选标签不受影响"
        }
    }
}
