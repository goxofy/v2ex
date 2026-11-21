import Foundation

@MainActor
final class TopicMoveStore: ObservableObject {
    struct Entry {
        let allowed: Bool
        let expires: Date
        let identity: String
    }

    enum ProbeState: Equatable {
        case queued, checking, allowed, denied
        case failed(MovePermissionIssue)
    }

    struct ProbeResult {
        let state: ProbeState
        let checkedAt: Date
        let identity: String
    }

    @Published private(set) var diagnostics: [Int: ProbeResult] = [:]
    @Published private(set) var permissions: [Int: Entry] = [:]
    @Published private(set) var movedNodes: [Int: MoveNode] = [:]
    @Published private(set) var changeRevision: UInt64 = 0
    private var identity = ""
    private var requests: [Int: Task<Void, Never>] = [:]
    private var requestTokens: [Int: UUID] = [:]
    private var consumers: [Int: Int] = [:]
    private var activeRequests = 0
    private var nodeOrigin = ""
    private struct CachedNode: Codable { let node: MoveNode; let expires: Date }
    private var nodeExpiry: [Int: Date] = [:]
    private var optimistic: Set<Int> = []
    private var expiryTasks: [Int: Task<Void, Never>] = [:]
    @Published private(set) var customShortcuts: [MoveNode]?

    private let defaults: UserDefaults
    private let now: () -> Date
    private let fetchPermission: (Int, String, URL) async throws -> MoveTopic

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = { Date() },
         fetchPermission: @escaping (Int, String, URL) async throws -> MoveTopic = { id, cookie, base in
             try await TopicMoveService(base: base).permission(id: id, cookie: cookie)
         }) {
        self.defaults = defaults
        self.now = now
        self.fetchPermission = fetchPermission
        loadNodes()
    }

    private func loadNodes() {
        nodeOrigin = V2EXEndpoint.base
        expiryTasks.values.forEach { $0.cancel() }
        expiryTasks.removeAll()
        optimistic.removeAll()
        let data = defaults.data(forKey: "topicMove.nodes.v2.\(nodeOrigin)")
        let cached = data.flatMap { try? JSONDecoder().decode([Int: CachedNode].self, from: $0) } ?? [:]
        let current = now()
        let valid = Dictionary(uniqueKeysWithValues: cached.filter {
            $0.key > 0 && $0.value.expires > current && $0.value.expires <= current.addingTimeInterval(300)
                && TopicMovePage.validSlug($0.value.node.name)
        }.sorted { $0.value.expires > $1.value.expires }.prefix(200).map { ($0.key, $0.value) })
        movedNodes = valid.mapValues(\.node)
        nodeExpiry = valid.mapValues(\.expires)
        for id in valid.keys { scheduleExpiry(id) }
        let favorites = defaults.data(forKey: "topicMove.shortcuts.\(nodeOrigin)")
        customShortcuts = favorites.flatMap { try? JSONDecoder().decode([MoveNode].self, from: $0) }.map(Self.cleanNodes)
    }

    private func saveNodes() -> Void {
        var values: [Int: CachedNode] = [:]
        for (id, node) in movedNodes where !optimistic.contains(id) {
            if let expires = nodeExpiry[id], expires > now() { values[id] = CachedNode(node: node, expires: expires) }
        }
        if let data = try? JSONEncoder().encode(values) {
            defaults.set(data, forKey: "topicMove.nodes.v2.\(nodeOrigin)")
        }
    }

    static func cleanNodes(_ nodes: [MoveNode]) -> [MoveNode] {
        var seen: Set<String> = []
        return nodes.filter { TopicMovePage.validSlug($0.name) && !$0.title.isEmpty && seen.insert($0.name).inserted }
    }

    func shortcuts(defaults website: [MoveNode], directory: [MoveNode]) -> [MoveNode] {
        let current = Dictionary(Self.cleanNodes(directory).map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
        return Self.cleanNodes(customShortcuts ?? website).map { current[$0.name] ?? $0 }
    }

    func saveShortcuts(_ nodes: [MoveNode]?) -> Void {
        customShortcuts = nodes.map(Self.cleanNodes)
        let key = "topicMove.shortcuts.\(nodeOrigin)"
        if let customShortcuts, let data = try? JSONEncoder().encode(customShortcuts) { defaults.set(data, forKey: key) }
        else { defaults.removeObject(forKey: key) }
    }

    func expireNodes() -> Void {
        for id in Array(nodeExpiry.keys) where nodeExpiry[id, default: .distantPast] <= now() {
            movedNodes.removeValue(forKey: id)
            nodeExpiry.removeValue(forKey: id)
            optimistic.remove(id)
            expiryTasks.removeValue(forKey: id)?.cancel()
        }
        saveNodes()
    }

    private func scheduleExpiry(_ id: Int) -> Void {
        expiryTasks[id]?.cancel()
        let seconds = max(0, (nodeExpiry[id] ?? now()).timeIntervalSince(now()))
        expiryTasks[id] = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(min(seconds, 300) * 1_000_000_000)) }
            catch { return }
            self?.expireNodes()
        }
    }

    func key(session: V2EXSessionStore) -> String { "\(session.revision)|\(V2EXEndpoint.base)" }

    func allowed(_ id: Int, session: V2EXSessionStore) -> Bool {
        guard session.isLoggedIn, let entry = permissions[id] else { return false }
        return entry.identity == key(session: session) && entry.expires > now() && entry.allowed
    }

    func synchronize(session: V2EXSessionStore) {
        let next = key(session: session)
        guard identity != next else { return }
        identity = next
        requests.values.forEach { $0.cancel() }
        requests.removeAll()
        requestTokens.removeAll()
        permissions.removeAll()
        diagnostics.removeAll()
        if nodeOrigin != V2EXEndpoint.base { loadNodes() }
    }

    func watch(_ id: Int, session: V2EXSessionStore) async {
        synchronize(session: session)
        guard session.isLoggedIn, URL(string: V2EXEndpoint.base)?.scheme == "https" else { return }
        if allowed(id, session: session) { return }
        await checkPermission(id, session: session)
    }

    func diagnostic(_ id: Int, session: V2EXSessionStore) -> ProbeResult? {
        guard let result = diagnostics[id], result.identity == key(session: session) else { return nil }
        return result
    }

    /// Explicit read-only recheck shares the same queue and result used by detail controls.
    func checkPermission(_ id: Int, session: V2EXSessionStore) async {
        synchronize(session: session)
        guard id > 0, session.isLoggedIn, URL(string: V2EXEndpoint.base)?.scheme == "https" else { return }
        consumers[id, default: 0] += 1
        defer { releaseConsumer(id) }
        if requests[id] == nil {
            permissions.removeValue(forKey: id)
            startProbe(id, session: session)
        }
        while requests[id] != nil, !Task.isCancelled {
            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
        }
    }

    private func releaseConsumer(_ id: Int) {
        consumers[id, default: 1] -= 1
        if consumers[id] == 0 {
            consumers.removeValue(forKey: id)
            requests.removeValue(forKey: id)?.cancel()
            requestTokens.removeValue(forKey: id)
            if diagnostics[id]?.state == .queued || diagnostics[id]?.state == .checking {
                diagnostics.removeValue(forKey: id)
            }
        }
    }

    private func publish(_ state: ProbeState, id: Int, generation: String) {
        if diagnostics.count >= 200, diagnostics[id] == nil,
           let oldest = diagnostics.min(by: { $0.value.checkedAt < $1.value.checkedAt })?.key {
            diagnostics.removeValue(forKey: oldest)
        }
        diagnostics[id] = ProbeResult(state: state, checkedAt: now(), identity: generation)
    }

    private func startProbe(_ id: Int, session: V2EXSessionStore) {
        guard requests[id] == nil, let base = URL(string: V2EXEndpoint.base) else { return }
        let generation = identity
        let cookie = session.cookie
        let nodeRevision = changeRevision
        let token = UUID()
        requestTokens[id] = token
        publish(.queued, id: id, generation: generation)
        requests[id] = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.requestTokens[id] == token {
                    self.requests.removeValue(forKey: id)
                    self.requestTokens.removeValue(forKey: id)
                    if self.diagnostics[id]?.state == .queued || self.diagnostics[id]?.state == .checking {
                        self.diagnostics.removeValue(forKey: id)
                    }
                }
            }
            while self.activeRequests >= 2 {
                do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
            }
            guard !Task.isCancelled, generation == self.key(session: session) else { return }
            self.activeRequests += 1
            defer { self.activeRequests -= 1 }
            self.publish(.checking, id: id, generation: generation)
            let result: Result<MoveTopic, Error>
            do { result = .success(try await self.fetchPermission(id, cookie, base)) }
            catch {
                guard !error.isCancellation else { return }
                result = .failure(error)
            }
            guard !Task.isCancelled, self.identity == generation, generation == self.key(session: session),
                  self.requestTokens[id] == token, nodeRevision == self.changeRevision else { return }
            switch result {
            case .success(let topic):
                if self.movedNodes[id] != nil, self.movedNodes[id] != topic.node {
                    self.storeNode(topic.node, id: id, optimistic: false)
                }
                if self.permissions.count >= 200, let oldest = self.permissions.min(by: { $0.value.expires < $1.value.expires })?.key {
                    self.permissions.removeValue(forKey: oldest)
                }
                self.permissions[id] = Entry(allowed: topic.canMove, expires: self.now().addingTimeInterval(60), identity: generation)
                self.publish(topic.canMove ? .allowed : .denied, id: id, generation: generation)
            case .failure(let error):
                let reason: MovePermissionIssue.Reason = (error as? MoveFailure) == .session ? .session : .network
                let issue = (error as? MovePermissionIssue) ?? MovePermissionIssue(stage: .topic, reason: reason)
                self.publish(.failed(issue), id: id, generation: generation)
            }
        }
    }

    func refreshMovedNode(_ id: Int, session: V2EXSessionStore) async -> Void {
        synchronize(session: session)
        guard movedNodes[id] != nil, session.isLoggedIn,
              let base = URL(string: V2EXEndpoint.base), base.scheme == "https" else { return }
        let generation = identity
        let before = changeRevision
        guard let node = try? await TopicMoveService(base: base).currentNode(id: id, cookie: session.cookie),
              !Task.isCancelled, generation == key(session: session), before == changeRevision else { return }
        storeNode(node, id: id, optimistic: false)
    }

    func invalidate(_ id: Int) {
        changeRevision &+= 1
        requests.removeValue(forKey: id)?.cancel()
        requestTokens.removeValue(forKey: id)
        permissions.removeValue(forKey: id)
        diagnostics.removeValue(forKey: id)
    }

    func record(_ node: MoveNode, topicID: Int) -> Void {
        storeNode(node, id: topicID, optimistic: false)
        invalidate(topicID)
    }

    func submitted(_ result: MoveSubmission, topicID: Int) -> Void {
        switch result {
        case .submitted(let node): storeNode(node, id: topicID, optimistic: true)
        case .unchanged(let node): storeNode(node, id: topicID, optimistic: false)
        }
        invalidate(topicID)
    }

    private func storeNode(_ node: MoveNode, id: Int, optimistic pending: Bool) -> Void {
        if movedNodes.count >= 200, movedNodes[id] == nil, let oldest = nodeExpiry.min(by: { $0.value < $1.value })?.key {
            nodeExpiry[oldest] = .distantPast
            expireNodes()
        }
        movedNodes[id] = node
        nodeExpiry[id] = now().addingTimeInterval(300)
        if pending { optimistic.insert(id) } else { optimistic.remove(id) }
        saveNodes()
        scheduleExpiry(id)
    }
}
