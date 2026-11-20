import SwiftUI

struct HomeView: View {
    let request: HomeOpenRequest
    var onCompose: () -> Void

    @StateObject private var model = HomeViewModel()
    @StateObject private var tabs = HomeTabsStore()
    @EnvironmentObject private var session: V2EXSessionStore
    @State private var editingTabs = false
    @EnvironmentObject private var followed: FollowedNodesStore
    @EnvironmentObject private var moderation: ModerationStore
    @EnvironmentObject private var readState: ReadStateStore
    @EnvironmentObject private var offline: OfflineStore
    @EnvironmentObject private var settings: AppSettings

    /// Per-feed scroll offsets, so swiping between categories doesn't lose your place.
    @State private var scrollPositions: [HomeViewModel.Feed: ScrollPosition] = [:]
    /// `.task` 会在从话题详情返回时重新启动。记录已经消费过的打开请求，
    /// 避免把用户当前分类重复重置为请求最初携带的「全部」。
    @State private var handledRequestID: UUID?

    private var feeds: [HomeViewModel.Feed] { tabs.visible(hackerNews: settings.hackerNewsEnabled) }

    private var visibleTopics: [V2Topic] {
        moderation.filter(model.topics).filter { model.feed == .following || !HomeViewModel.isPromotion($0) }
    }

    var body: some View {
        // One page per feed: swipe left/right to change category, the chip rail
        // above stays in sync through the shared `model.feed` selection.
        TabView(selection: $model.feed) {
            ForEach(feeds, id: \.self) { feed in
                feedPage(feed)
                    .tag(feed)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        // Let the feed scroll under the floating tab bar instead of stopping above it.
        .ignoresSafeArea(edges: .bottom)
        .background(Theme.canvas)
        .navigationTitle("V2EX")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .topSafeAreaBar(spacing: 0) { feedFilterBar }
        .sheet(isPresented: $editingTabs) {
            HomeTabsEditor(store: tabs, followedNodes: followed.names, hackerNews: settings.hackerNewsEnabled)
        }
        .onChange(of: feeds) { _, current in
            model.feed = current.first(where: { $0.id == model.feed.id }) ?? current[0]
        }
        .task(id: "\(session.revision)|\(V2EXEndpoint.base)") {
            model.synchronize(session: session)
            if model.feed == .following { await model.load(feed: .following, session: session) }
        }
        .task(id: request.id) {
            // NavigationStack cancels this task while a topic is on top and
            // starts it again after Back. The same request must be consumed
            // only once; otherwise its default `.all` feed overwrites the
            // category the user selected before opening the topic.
            guard handledRequestID != request.id else {
                // A cancelled first load may leave a genuinely empty page.
                // Refresh that same page without changing the selection.
                if model.feed != .hackerNews, model.topics.isEmpty, !model.isLoading {
                    await model.load(feed: model.feed, session: session)
                }
                return
            }
            handledRequestID = request.id

            let requested = feeds.first(where: { $0.id == request.feed.id }) ?? feeds[0]
            if model.feed == requested {
                await model.load(feed: requested, session: session)
            } else {
                // `onChange` owns the load after switching feeds, avoiding a
                // duplicate request when Siri opens 今日最热.
                model.feed = requested
            }
        }
        .onChange(of: model.feed) { _, newFeed in
            Task { await model.load(feed: newFeed, session: session) }
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button(action: onCompose) {
                Image(systemName: "square.and.pencil")
            }
            .accessibilityLabel("发布话题")
        }
    }

    private var feedFilterBar: some View {
        HStack(spacing: 4) {
            ChipRail(items: feeds, selected: model.feed) { feed in
                FilterChip(title: feed.title, isSelected: model.feed == feed) { model.feed = feed }
                    .id(feed)
            }
            Button { editingTabs = true } label: { Image(systemName: "slider.horizontal.3").padding(10) }
                .accessibilityLabel("编辑首页标签")
        }.readableColumn()
    }

    @ViewBuilder
    private func feedPage(_ feed: HomeViewModel.Feed) -> some View {
        if feed == .hackerNews {
            // 数据模型和 V2EX 完全不同（无节点、无附言、评论是嵌套树），
            // 所以自成一页而不是塞进上面的话题列表。
            HackerNewsView()
        } else {
        ScrollView {
            LazyVStack(spacing: 10) {
                if model.feed == feed { refreshStatus(for: feed) }
                if model.feed == feed, let message = model.errorMessage, !visibleTopics.isEmpty {
                    EmptyStateCard(icon: "wifi.exclamationmark", title: "刷新失败，当前显示上次内容", message: message,
                                   actionTitle: "重试") {
                        Task { await model.refresh(feed: feed, session: session, source: .retry) }
                    }
                }
                if let message = model.errorMessage, visibleTopics.isEmpty {
                    EmptyStateCard(icon: "wifi.exclamationmark", title: "没能加载", message: message,
                                   actionTitle: "重试") {
                        Task { await model.refresh(feed: feed, session: session, source: .retry) }
                    }
                } else if model.isLoading && visibleTopics.isEmpty {
                    LoadingCard()
                } else if visibleTopics.isEmpty {
                    EmptyStateCard(icon: "tray", title: feed == .following ? "关注的人还没有发布话题" : "这里还没有话题")
                } else {
                    // The public feed opens with a compact live map of where
                    // the current conversation is concentrated. It makes the
                    // first screen recognisably v2Explore without changing the
                    // familiar topic-list interaction below.
                    if feed == .all, settings.communityPulseEnabled {
                        CommunityPulseCard(topics: visibleTopics)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    // Lead card, then the rest as a grouped list — as in the design.
                    if let featured = visibleTopics.first {
                        NavigationLink(value: Route.topic(featured.id)) {
                            CardSection(padding: 16) {
                                FeaturedTopicCard(
                                    topic: featured,
                                    badge: feed == .hot
                                        ? "今日最热"
                                        : feed == .r2 ? "R2 排序" : "最新活跃"
                                )
                            }
                        }
                        .buttonStyle(.row)
                        // CardSection insets its content by 16 on top of the
                        // screen padding, so the corner sits further in here.
                        .promotionBadge(
                            for: featured,
                            trailing: Theme.Metric.screenPadding + 16,
                            top: 16
                        )
                    }

                    TopicListCard(items: Array(visibleTopics.dropFirst())) { topic in
                        NavigationLink(value: Route.topic(topic.id)) {
                            TopicRow(
                                topic: topic,
                                isOffline: offline.isOffline(topic.id),
                                isRead: readState.isRead(topic.id),
                                dimRead: settings.dimReadTopics
                            )
                        }
                        .buttonStyle(.row)
                        .promotionBadge(for: topic)
                    }
                }
                if model.feed == feed {
                    if let error = model.moreError {
                        Text(error).font(.footnote).foregroundStyle(Theme.muted)
                        Button("重试加载更多") { Task { await model.loadMore(feed: feed) } }
                    } else if model.hasMore {
                        ProgressView()
                            .padding()
                            .task(id: model.paginationID) { await model.loadMore(feed: feed) }
                    } else if !model.topics.isEmpty {
                        Text(feed == .hot || feed == .r2 ? "已展示全部榜单话题" : "没有更多话题了")
                            .font(.footnote).foregroundStyle(Theme.muted).padding()
                    }
                }
            }
            .readableColumn()
            .padding(.top, 10)
            // Room for the floating tab bar + home indicator at the bottom.
            .padding(.bottom, 100)
        }
        .scrollIndicators(.hidden)
        .pullToRefresh {
            await model.refresh(feed: feed, session: session, source: .pull)
        }
        .scrollPosition(scrollBinding(for: feed))
        }
    }

    private func refreshStatus(for feed: HomeViewModel.Feed) -> some View {
        let state = model.refreshState(for: feed)
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                switch state.phase {
                case .idle: Text("尚未获取列表")
                case .refreshing: Text("正在刷新…")
                case .success(let unchanged): Text(unchanged ? "已获取，列表无变化" : "已获取列表")
                case .failed: Text("刷新失败，保留上次内容")
                case .cancelled: Text("刷新已取消，可重试")
                }
                if let date = state.lastSuccess {
                    Text("最近成功获取 \(date.formatted(.dateTime.hour().minute().second()))")
                }
            }
            .font(.footnote)
            .foregroundStyle(Theme.muted)
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Button("立即刷新", systemImage: "arrow.clockwise") {
                Task { await model.refresh(feed: feed, session: session, source: .button) }
            }
            .font(.footnote)
            .disabled(state.phase == .refreshing)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }

    private func scrollBinding(for feed: HomeViewModel.Feed) -> Binding<ScrollPosition> {
        Binding(
            get: { scrollPositions[feed] ?? ScrollPosition() },
            set: { scrollPositions[feed] = $0 }
        )
    }
}

// MARK: - Community pulse

private struct CommunityPulseCard: View {
    private struct Signal: Identifiable {
        let name: String
        let title: String
        let topicCount: Int
        let replies: Int
        var id: String { name }
        var score: Int { replies + topicCount * 2 }
    }

    let topics: [V2Topic]
    @EnvironmentObject private var radar: RadarStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var signals: [Signal] {
        var buckets: [String: (title: String, topics: Int, replies: Int)] = [:]
        for topic in topics.prefix(30) {
            guard let node = topic.node, !node.name.isEmpty else { continue }
            var bucket = buckets[node.name] ?? (node.title, 0, 0)
            bucket.topics += 1
            bucket.replies += topic.replies
            buckets[node.name] = bucket
        }
        let mapped: [Signal] = buckets.map { entry in
            let name = entry.key
            let value = entry.value
            return Signal(
                name: name,
                title: value.title,
                topicCount: value.topics,
                replies: value.replies
            )
        }
        let sorted = mapped.sorted { lhs, rhs in
            if lhs.score == rhs.score { return lhs.title < rhs.title }
            return lhs.score > rhs.score
        }
        return Array(sorted.prefix(3))
    }

    private var maxScore: Int { max(signals.map(\.score).max() ?? 1, 1) }
    private var sampledTopicCount: Int { min(topics.count, 30) }
    private var sampledReplyCount: Int { topics.prefix(30).reduce(0) { $0 + $1.replies } }

    var body: some View {
        CardSection(padding: 16) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("社区脉搏")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(Theme.ink)
                        Text("当前讨论集中在哪里")
                            .font(Type.meta(11))
                            .foregroundStyle(Theme.muted)
                    }
                    Spacer(minLength: 8)
                    NavigationLink(value: Route.radar) {
                        HStack(spacing: 6) {
                            Image(systemName: "waveform.path.ecg")
                                .font(.system(size: 14, weight: .medium))
                            Text(radar.rules.isEmpty ? "雷达" : "雷达 \(radar.rules.count)")
                                .font(Type.meta(11))
                        }
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 11)
                        .frame(height: 34)
                        .glassPill()
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("打开关键词雷达，\(radar.rules.count) 条规则")
                }

                VStack(spacing: 8) {
                    ForEach(signals) { signal in
                        NavigationLink(value: Route.node(signal.name)) {
                            HStack(spacing: 10) {
                                Text(signal.title)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Theme.body)
                                    .lineLimit(1)
                                    .frame(width: 68, alignment: .leading)

                                GeometryReader { proxy in
                                    let progress = CGFloat(signal.score) / CGFloat(maxScore)
                                    ZStack(alignment: .leading) {
                                        Capsule().fill(Theme.inset)
                                        Capsule()
                                            .fill(Theme.accent.opacity(0.28))
                                            .frame(width: max(10, proxy.size.width * progress))
                                        Circle()
                                            .fill(Theme.accent)
                                            .frame(width: 7, height: 7)
                                            .offset(x: max(3, proxy.size.width * progress - 7))
                                    }
                                    .animation(reduceMotion ? nil : .snappy(duration: 0.3), value: progress)
                                }
                                .frame(height: 7)

                                Text("\(signal.replies)")
                                    .font(Type.number(12, weight: .medium))
                                    .foregroundStyle(Theme.muted)
                                    .frame(minWidth: 28, alignment: .trailing)
                            }
                            .frame(minHeight: 28)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.row)
                        .accessibilityLabel(
                            "\(signal.title)，\(signal.topicCount) 个话题，\(signal.replies) 条回复"
                        )
                    }
                }

                Text("采样 \(sampledTopicCount) 个话题 · \(sampledReplyCount) 条回复")
                    .font(Type.meta(10))
                    .foregroundStyle(Theme.faint)
            }
        }
    }
}
