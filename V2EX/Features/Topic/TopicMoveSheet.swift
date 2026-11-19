import SwiftUI

struct TopicMoveSheet: View {
    let topic: V2Topic
    var onSubmitted: (MoveSubmission) -> Void = { _ in }
    @EnvironmentObject private var session: V2EXSessionStore
    @EnvironmentObject private var moves: TopicMoveStore
    @Environment(\.dismiss) private var dismiss
    @State private var preparation: MovePreparation?
    @State private var selected: MoveNode?
    @State private var query = ""
    @State private var memo = ""
    @State private var error: String?
    @State private var busy = false
    @State private var blocked = false
    @State private var confirm = false
    @State private var editingShortcuts = false
    @State private var operation: Task<Void, Never>?
    @State private var identity = ""
    @FocusState private var focused: Field?
    private enum Field: Hashable { case search, memo }

    var body: some View {
        NavigationStack {
            Form {
                Section("话题 #\(topic.id)") {
                    Text(topic.title)
                    if let preparation { Text("当前节点：\(preparation.topic.node.title)").foregroundStyle(.secondary) }
                }
                if busy { ProgressView("正在处理…") }
                if let error {
                    Section {
                        Text(error).foregroundStyle(.red)
                        Link("到网页查看", destination: V2EXEndpoint.url("/t/\(topic.id)"))
                    }
                }
                if let preparation {
                    Section {
                        let nodes = moves.shortcuts(defaults: preparation.form.shortcuts, directory: preparation.nodes)
                        if nodes.isEmpty { Text("尚未设置常用节点，点击编辑添加").foregroundStyle(.secondary) }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], alignment: .leading, spacing: 8) {
                            ForEach(nodes) { node in
                                Button { select(node) } label: {
                                    Text(node.title).frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.bordered)
                                .tint(selected?.name == node.name ? .accentColor : .secondary)
                                .disabled(blocked)
                            }
                        }
                    } header: {
                        HStack {
                            Text("常用节点")
                            Spacer()
                            Button("编辑") { focused = nil; editingShortcuts = true }.disabled(blocked)
                        }
                    }
                    Section("目标节点") {
                        TextField("搜索节点名称、英文标识或别名", text: $query)
                            .focused($focused, equals: .search).disabled(blocked)
                        if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            let results = preparation.nodes.filter { $0.matches(query) }
                            if results.isEmpty { Text("没有匹配的节点").foregroundStyle(.secondary) }
                            else {
                                ScrollView {
                                    LazyVStack(alignment: .leading, spacing: 0) {
                                        ForEach(results) { node in
                                            Button { select(node) } label: {
                                                HStack {
                                                    Text("\(node.title) / \(node.name)")
                                                    Spacer()
                                                    if selected?.name == node.name { Image(systemName: "checkmark") }
                                                }.padding(.vertical, 12).contentShape(Rectangle())
                                            }.buttonStyle(.plain).disabled(blocked)
                                            Divider()
                                        }
                                    }
                                }.frame(height: min(CGFloat(results.count) * 48, 240))
                            }
                        }
                        if let selected { Label("已选：\(selected.title) / \(selected.name)", systemImage: "checkmark.circle.fill") }
                    }
                    Section("操作备注（可选）") {
                        TextField("备注", text: $memo, axis: .vertical).lineLimit(2...5)
                            .focused($focused, equals: .memo).disabled(blocked)
                        Text("\(memo.count)/2000").font(.caption).foregroundStyle(.secondary)
                    }
                } else if !busy, !blocked {
                    Button("重新读取权限与表单") { load() }
                }
            }
            .disabled(busy)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                if let preparation {
                    Button {
                        focused = nil
                        confirm = true
                    } label: {
                        Text(busy ? "正在处理…" : "移动主题").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || blocked || selected == nil || selected?.name == preparation.topic.node.name || memo.count > 2000)
                    .padding().background(.regularMaterial)
                }
            }
            .navigationTitle("移动主题")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }
            .alert("确认移动主题？", isPresented: $confirm) {
                Button("取消", role: .cancel) {}
                Button("确认移动") { submit() }
            } message: {
                Text("\(topic.title)\n\(preparation?.topic.node.title ?? "") → \(selected?.title ?? "")")
            }
            .sheet(isPresented: $editingShortcuts) {
                if let preparation { MoveShortcutsEditor(preparation: preparation) }
            }
        }
        .interactiveDismissDisabled(busy)
        .onAppear { identity = moves.key(session: session); load() }
        .onChange(of: moves.key(session: session)) { _, _ in operation?.cancel(); dismiss() }
        .onDisappear { operation?.cancel() }
    }

    private func select(_ node: MoveNode) -> Void {
        selected = node
        query = ""
        focused = nil
    }

    private func load() -> Void {
        guard !busy, let base = URL(string: V2EXEndpoint.base), base.scheme == "https" else { return }
        busy = true
        error = nil
        let cookie = session.cookie
        operation = Task {
            defer { busy = false }
            do {
                let value = try await TopicMoveService(base: base).prepare(id: topic.id, cookie: cookie)
                guard !Task.isCancelled, identity == moves.key(session: session) else { return }
                preparation = value
            } catch {
                guard !error.isCancellation else { return }
                self.error = (error as? MoveFailure)?.errorDescription ?? MoveFailure.unavailable.errorDescription
                moves.invalidate(topic.id)
            }
        }
    }

    private func submit() -> Void {
        guard !busy, !blocked, let selected, let preparation, let base = URL(string: V2EXEndpoint.base) else { return }
        busy = true
        error = nil
        let cookie = session.cookie
        operation = Task {
            defer { busy = false }
            do {
                let result = try await TopicMoveService(base: base).move(id: topic.id, cookie: cookie,
                    expectedNode: preparation.topic.node.name, destination: selected, memo: memo,
                    sessionCurrent: { identity == moves.key(session: session) })
                guard !Task.isCancelled, identity == moves.key(session: session) else { return }
                moves.submitted(result, topicID: topic.id)
                onSubmitted(result)
                dismiss()
            } catch {
                guard !error.isCancellation else { return }
                self.error = (error as? MoveFailure)?.errorDescription ?? MoveFailure.connection.errorDescription
                moves.invalidate(topic.id)
                blocked = true
            }
        }
    }
}

private struct MoveShortcutsEditor: View {
    let preparation: MovePreparation
    @EnvironmentObject private var moves: TopicMoveStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: [MoveNode] = []
    @State private var query = ""
    @State private var restoreDefault = false

    var body: some View {
        NavigationStack {
            List {
                Section("我的常用节点（可删除、拖动排序）") {
                    if draft.isEmpty { Text("常用节点为空").foregroundStyle(.secondary) }
                    ForEach(draft) { node in Text("\(node.title) / \(node.name)") }
                        .onDelete { draft.remove(atOffsets: $0); restoreDefault = false }
                        .onMove { draft.move(fromOffsets: $0, toOffset: $1); restoreDefault = false }
                }
                Section("添加节点") {
                    TextField("搜索节点", text: $query)
                    if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        let results = preparation.nodes.filter { node in node.matches(query) && !draft.contains(where: { $0.name == node.name }) }
                        if results.isEmpty { Text("没有可添加的匹配节点").foregroundStyle(.secondary) }
                        ForEach(results) { node in
                            Button { draft.append(node); restoreDefault = false } label: {
                                Label("\(node.title) / \(node.name)", systemImage: "plus.circle")
                            }
                        }
                    }
                }
                Section {
                    Button("恢复官网默认") { draft = preparation.form.shortcuts; restoreDefault = true }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("编辑常用节点")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { moves.saveShortcuts(restoreDefault ? nil : draft); dismiss() }
                }
            }
        }
        .onAppear { draft = moves.shortcuts(defaults: preparation.form.shortcuts, directory: preparation.nodes) }
    }
}

struct TopicMoveDiagnosticsView: View {
    @EnvironmentObject private var session: V2EXSessionStore
    @EnvironmentObject private var moves: TopicMoveStore
    @State private var input = ""
    @State private var checkedID: Int?
    @State private var busy = false
    @State private var operation: Task<Void, Never>?

    private var topicID: Int? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.utf8.allSatisfy({ (48...57).contains($0) }),
              let id = Int(value), id > 0 else { return nil }
        return id
    }

    var body: some View {
        Form {
            Section("当前连接") {
                LabeledContent("站点", value: V2EXEndpoint.base)
                LabeledContent("App 网页会话", value: session.isLoggedIn ? "已保存（有效性由请求核实）" : "未登录")
                Text("Access Token 和浏览器中的登录状态不能代替 App 内的网页登录。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("只读权限检查") {
                TextField("话题 ID，例如 123", text: $input).keyboardType(.numberPad)
                    .disabled(busy)
                Button("检查此话题权限") { check() }
                    .disabled(busy || topicID == nil || !session.isLoggedIn)
                if busy { ProgressView("正在检查网页权限…") }
                Text("只读取话题页及必要的移动表单，不提交任何移动。请使用你在官网有权管理的话题 ID。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let checkedID {
                Section("话题 #\(checkedID) 的检查结果") {
                    if let result = moves.diagnostic(checkedID, session: session) {
                        resultContent(result, id: checkedID)
                        Text(result.checkedAt, style: .time).font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text(busy ? "等待检查结果" : "结果已清除或检查取消，请重新检查。")
                    }
                }
            }
            Section {
                Text("诊断只显示固定原因码，不显示或上传 Cookie、网页内容、用户名及隐藏验证字段。检查成功后返回话题页，在右上角 ⋯ 菜单查看「移动主题」。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("网页权限诊断")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: moves.key(session: session)) { _, _ in
            operation?.cancel()
            busy = false
            checkedID = nil
        }
        .onDisappear { operation?.cancel() }
    }

    @ViewBuilder
    private func resultContent(_ result: TopicMoveStore.ProbeResult, id: Int) -> some View {
        switch result.state {
        case .queued: Text("正在等待检查名额（最多两个并发）")
        case .checking: Text("正在读取话题页及必要的移动表单")
        case .allowed:
            Text(moves.allowed(id, session: session) ? "已确认移动权限" : "此前检查通过，权限缓存已过期，请重新检查。")
        case .denied: Text("此话题的权限检查未获允许。")
        case .failed(let issue):
            LabeledContent("检查阶段", value: issue.stage.rawValue)
            LabeledContent("原因码", value: issue.code)
            Text(issue.message)
        }
    }

    private func check() {
        guard !busy, let topicID else { return }
        checkedID = topicID
        busy = true
        operation = Task {
            await moves.checkPermission(topicID, session: session)
            guard !Task.isCancelled else { return }
            busy = false
        }
    }
}
