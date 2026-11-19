import SwiftUI

struct TopicMoveSheet: View {
    let topic: V2Topic
    @EnvironmentObject private var session: V2EXSessionStore
    @EnvironmentObject private var moves: TopicMoveStore
    @EnvironmentObject private var cache: TopicDetailCacheStore
    @Environment(\.dismiss) private var dismiss
    @State private var preparation: MovePreparation?
    @State private var selected: MoveNode?
    @State private var query = ""
    @State private var memo = ""
    @State private var error: String?
    @State private var busy = false
    @State private var blocked = false
    @State private var confirm = false
    @State private var operation: Task<Void, Never>?
    @State private var identity = ""

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
                        Link("到网页核查", destination: V2EXEndpoint.url("/t/\(topic.id)"))
                    }
                }
                if let preparation {
                    if !preparation.form.shortcuts.isEmpty {
                        Section("常用节点") {
                            ScrollView(.horizontal) {
                                HStack {
                                    ForEach(preparation.form.shortcuts) { node in
                                        Button(node.title) { selected = node }
                                            .buttonStyle(.bordered)
                                            .disabled(blocked)
                                    }
                                }
                            }
                        }
                    }
                    Section("目标节点") {
                        TextField("搜索节点名称、英文标识或别名", text: $query)
                        if let selected { Label("\(selected.title) / \(selected.name)", systemImage: "checkmark.circle.fill") }
                    }
                    Section("操作备注（可选）") {
                        TextField("备注", text: $memo, axis: .vertical).lineLimit(2...5).disabled(blocked)
                        Text("\(memo.count)/2000").font(.caption).foregroundStyle(.secondary)
                    }
                    Section {
                        Button("移动主题") { confirm = true }
                            .disabled(selected == nil || selected?.name == preparation.topic.node.name || memo.count > 2000 || blocked)
                    }
                    Section("搜索结果（最多显示 60 项）") {
                        ForEach(Array(preparation.nodes.filter { query.isEmpty || $0.matches(query) }.prefix(60))) { node in
                            Button { selected = node } label: {
                                HStack {
                                    Text("\(node.title) / \(node.name)")
                                    Spacer()
                                    if selected?.name == node.name { Image(systemName: "checkmark") }
                                }
                            }
                            .disabled(blocked)
                        }
                    }
                } else if !busy, !blocked {
                    Button("重新读取权限与表单") { load() }
                }
            }
            .disabled(busy)
            .navigationTitle("移动主题")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }
            .confirmationDialog("确认移动主题？", isPresented: $confirm, titleVisibility: .visible) {
                Button("确认移动") { submit() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("\(preparation?.topic.node.title ?? "") → \(selected?.title ?? "")\n\(topic.title)")
            }
        }
        .interactiveDismissDisabled(busy)
        .onAppear { identity = moves.key(session: session); load() }
        .onChange(of: moves.key(session: session)) { _, _ in operation?.cancel(); dismiss() }
        .onDisappear { operation?.cancel() }
    }

    private func load() {
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

    private func submit() {
        guard !busy, !blocked, let selected, let preparation, let base = URL(string: V2EXEndpoint.base) else { return }
        busy = true
        error = nil
        let cookie = session.cookie
        operation = Task {
            defer { busy = false }
            do {
                let node = try await TopicMoveService(base: base).move(id: topic.id, cookie: cookie,
                    expectedNode: preparation.topic.node.name, destination: selected, memo: memo,
                    sessionCurrent: { identity == moves.key(session: session) })
                guard !Task.isCancelled, identity == moves.key(session: session) else { return }
                cache.updateNode(node, topicID: topic.id)
                moves.record(node, topicID: topic.id)
                dismiss()
            } catch {
                guard !error.isCancellation else { return }
                self.error = (error as? MoveFailure)?.errorDescription ?? MoveFailure.unconfirmed.errorDescription
                moves.invalidate(topic.id)
                blocked = true
                // A fresh panel is required after a failed mutation; never offer an automatic retry.
            }
        }
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

private struct TopicMoveRowModifier: ViewModifier {
    let topic: V2Topic
    @EnvironmentObject private var session: V2EXSessionStore
    @EnvironmentObject private var moves: TopicMoveStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var presented = false

    func body(content: Content) -> some View {
        VStack(spacing: 0) {
            content
            if moves.allowed(topic.id, session: session) {
                HStack {
                    Spacer()
                    Button { presented = true } label: { Label("移动", systemImage: "arrow.right.square") }
                        .buttonStyle(.borderless).font(.caption)
                }.padding(.horizontal, 16).padding(.bottom, 10)
            }
        }
        .task(id: "\(moves.key(session: session))|\(scenePhase == .active)") {
            if scenePhase == .active { await moves.watch(topic.id, session: session) }
        }
        .sheet(isPresented: $presented) { TopicMoveSheet(topic: topic) }
        .onChange(of: moves.key(session: session)) { _, _ in presented = false }
    }
}

extension View {
    func topicMoveAction(for topic: V2Topic) -> some View { modifier(TopicMoveRowModifier(topic: topic)) }
}
