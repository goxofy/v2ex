import Foundation

@MainActor
final class TopicMoveStore: ObservableObject {
    struct Entry {
        let allowed: Bool
        let expires: Date
        let identity: String
    }

    @Published private(set) var permissions: [Int: Entry] = [:]
    @Published private(set) var movedNodes: [Int: MoveNode] = [:]
    @Published private(set) var changeRevision: UInt64 = 0
    private var identity = ""
    private var requests: [Int: Task<Void, Never>] = [:]
    private var requestTokens: [Int: UUID] = [:]
    private var consumers: [Int: Int] = [:]
    private var activeRequests = 0
    private var retryAfter: [Int: Date] = [:]
    private var nodeOrigin = ""

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
        let data = defaults.data(forKey: "topicMove.nodes.\(nodeOrigin)")
        movedNodes = data.flatMap { try? JSONDecoder().decode([Int: MoveNode].self, from: $0) } ?? [:]
    }

    private func saveNodes() {
        if let data = try? JSONEncoder().encode(movedNodes) {
            defaults.set(data, forKey: "topicMove.nodes.\(nodeOrigin)")
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
        retryAfter.removeAll()
        if nodeOrigin != V2EXEndpoint.base { loadNodes() }
    }

    func watch(_ id: Int, session: V2EXSessionStore) async {
        synchronize(session: session)
        guard session.isLoggedIn, URL(string: V2EXEndpoint.base)?.scheme == "https" else { return }
        let generation = identity
        consumers[id, default: 0] += 1
        defer {
            consumers[id, default: 1] -= 1
            if consumers[id] == 0 {
                consumers.removeValue(forKey: id)
                retryAfter.removeValue(forKey: id)
                requests.removeValue(forKey: id)?.cancel()
                requestTokens.removeValue(forKey: id)
            }
        }
        while !Task.isCancelled, generation == key(session: session) {
            if permissions[id]?.expires ?? .distantPast <= now() {
                permissions.removeValue(forKey: id)
                if requests[id] == nil, retryAfter[id] ?? .distantPast <= now() {
                    let cookie = session.cookie
                    let nodeRevision = changeRevision
                    guard let base = URL(string: V2EXEndpoint.base) else { return }
                    let token = UUID()
                    requestTokens[id] = token
                    requests[id] = Task { [weak self] in
                        guard let self else { return }
                        defer {
                            if self.requestTokens[id] == token {
                                self.requests.removeValue(forKey: id)
                                self.requestTokens.removeValue(forKey: id)
                            }
                        }
                        while self.activeRequests >= 2 {
                            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
                        }
                        guard !Task.isCancelled else { return }
                        self.activeRequests += 1
                        defer { self.activeRequests -= 1 }
                        let result = try? await self.fetchPermission(id, cookie, base)
                        guard !Task.isCancelled, self.identity == generation, generation == self.key(session: session) else { return }
                        guard nodeRevision == self.changeRevision else { return }
                        self.retryAfter[id] = now().addingTimeInterval(60)
                        if let result {
                            if self.movedNodes[id] != nil, self.movedNodes[id] != result.node {
                                self.movedNodes[id] = result.node
                                self.saveNodes()
                            }
                            if self.permissions.count >= 200, let oldest = self.permissions.min(by: { $0.value.expires < $1.value.expires })?.key {
                                self.permissions.removeValue(forKey: oldest)
                            }
                            self.permissions[id] = Entry(allowed: result.canMove, expires: now().addingTimeInterval(60), identity: generation)
                        }
                    }
                }
            }
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
        }
    }

    func refreshMovedNode(_ id: Int, session: V2EXSessionStore) async {
        synchronize(session: session)
        guard movedNodes[id] != nil, session.isLoggedIn,
              let base = URL(string: V2EXEndpoint.base), base.scheme == "https" else { return }
        let generation = identity
        let before = changeRevision
        let cookie = session.cookie
        while activeRequests >= 2 {
            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
        }
        guard !Task.isCancelled else { return }
        activeRequests += 1
        defer { activeRequests -= 1 }
        guard let result = try? await fetchPermission(id, cookie, base),
              !Task.isCancelled, generation == key(session: session), before == changeRevision else { return }
        movedNodes[id] = result.node
        saveNodes()
    }

    func invalidate(_ id: Int) {
        changeRevision &+= 1
        requests.removeValue(forKey: id)?.cancel()
        requestTokens.removeValue(forKey: id)
        permissions.removeValue(forKey: id)
        retryAfter.removeValue(forKey: id)
    }

    func record(_ node: MoveNode, topicID: Int) {
        if movedNodes.count >= 200, movedNodes[topicID] == nil, let oldest = movedNodes.keys.min() {
            movedNodes.removeValue(forKey: oldest)
        }
        movedNodes[topicID] = node
        saveNodes()
        invalidate(topicID)
    }
}
