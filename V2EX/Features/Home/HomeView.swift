import SwiftUI

struct HomeView: View {
    let request: HomeOpenRequest
    var onCompose: () -> Void

    @StateObject private var model = HomeViewModel()
    @StateObject private var tabs = HomeTabsStore()
    @EnvironmentObject private var session: V2EXSessionStore
    @State private var editingTabs = false
    @State private var refreshFeedback: HomeRefreshFeedback?
    @State private var isHomeVisible = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var followed: FollowedNodesStore
    @EnvironmentObject private var moderation: ModerationStore
    @EnvironmentObject private var readState: ReadStateStore
    @EnvironmentObject private var offline: OfflineStore
    @EnvironmentObject private var settings: AppSettings

    /// Per-feed scroll offsets, so swiping between categories doesn't lose your place.
    @State private var scrollPositions: [HomeViewModel.Feed: ScrollPosition] = [:]
    @State private var nodeScroll = HomeNodeScrollStates()
    /// `.task` 会在从话题详情返回时重新启动。记录已经消费过的打开请求，
    /// 避免把用户当前分类重复重置为请求最初携带的「全部」。
    @State private var handledRequestID: UUID?

    private var feeds: [HomeViewModel.Feed] { tabs.visible(hackerNews: settings.hackerNewsEnabled) }

    private var visibleTopics: [V2Topic] {
        moderation.filter(model.topics).filter { model.feed == .following || !HomeViewModel.isPromotion($0) }
    }

    var body: some View {
        HomeNodeScrollContainer(feeds: feeds, selected: $model.feed,
                                state: nodeScroll.state(for: model.feed),
                                content: feedPages.overlay(alignment: .top) { refreshToast }) {
            editingTabs = true
        }
        // Let the feed scroll under the floating tab bar instead of stopping above it.
        .ignoresSafeArea(edges: .bottom)
        .background(Theme.canvas)
        .navigationTitle("V2EX")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
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
            nodeScroll.interruptAll()
            nodeScroll.state(for: newFeed).settle(reduceMotion: reduceMotion)
            refreshFeedback = nil
            Task { await model.load(feed: newFeed, session: session) }
        }
        .onChange(of: model.refreshStates) { previous, current in
            let feed = model.feed
            guard isHomeVisible, let state = current[feed] else {
                refreshFeedback = nil
                return
            }
            if state.phase == .refreshing {
                refreshFeedback = nil
            } else if let feedback = HomeRefreshFeedback(feed: feed, previous: previous[feed], current: state) {
                refreshFeedback = feedback
            }
        }
        .onAppear {
            isHomeVisible = true
            nodeScroll.state(for: model.feed).settle(reduceMotion: reduceMotion)
        }
        .onDisappear {
            nodeScroll.interruptAll()
            isHomeVisible = false
            refreshFeedback = nil
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

    private var feedPages: some View {
        TabView(selection: $model.feed) {
            ForEach(feeds, id: \.self) { feed in
                feedPage(feed)
                    .onScrollGeometryChange(for: HomeNodeScrollSample.self) { geometry in
                        HomeNodeScrollSample(offset: geometry.contentOffset.y + geometry.contentInsets.top,
                                             topInset: geometry.contentInsets.top,
                                             viewportHeight: geometry.containerSize.height)
                    } action: { _, current in
                        guard model.feed == feed else { return }
                        nodeScroll.state(for: feed).receive(current)
                    }
                    .onScrollPhaseChange { _, phase, context in
                        guard model.feed == feed else { return }
                        let state = nodeScroll.state(for: feed)
                        let geometry = context.geometry
                        state.receive(HomeNodeScrollSample(offset: geometry.contentOffset.y + geometry.contentInsets.top,
                                                           topInset: geometry.contentInsets.top,
                                                           viewportHeight: geometry.containerSize.height))
                        if phase == .idle { state.settle(reduceMotion: reduceMotion) }
                        else { state.interrupt() }
                    }
                    .tag(feed)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    @ViewBuilder
    private func feedPage(_ feed: HomeViewModel.Feed) -> some View {
        let refreshError = model.feed == feed
            ? HomeRefreshFeedback.retryMessage(error: model.errorMessage, phase: model.refreshState(for: feed).phase)
            : nil
        if feed == .hackerNews {
            // 数据模型和 V2EX 完全不同（无节点、无附言、评论是嵌套树），
            // 所以自成一页而不是塞进上面的话题列表。
            HackerNewsView()
        } else {
        ScrollView {
            LazyVStack(spacing: 10) {
                if let message = refreshError, !visibleTopics.isEmpty {
                    EmptyStateCard(icon: "wifi.exclamationmark", title: "刷新失败，当前显示上次内容", message: message,
                                   actionTitle: "重试") {
                        Task { await model.refresh(feed: feed, session: session, source: .retry) }
                    }
                }
                if let message = refreshError, visibleTopics.isEmpty {
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

    @ViewBuilder
    private var refreshToast: some View {
        if let feedback = refreshFeedback, feedback.feed == model.feed {
            HStack(spacing: 8) {
                Image(systemName: feedback.icon).foregroundStyle(Theme.accent)
                VStack(alignment: .leading, spacing: 3) {
                    Text(feedback.title).foregroundStyle(Theme.ink)
                    if let date = feedback.lastSuccess {
                        Text("最近成功获取 \(date.formatted(.dateTime.hour().minute().second()))")
                            .foregroundStyle(Theme.muted)
                    }
                }
                .font(.footnote)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            .shadow(color: .black.opacity(0.08), radius: 8, y: 3)
            .padding(.horizontal, Theme.Metric.screenPadding)
            .padding(.top, 8)
            .accessibilityElement(children: .combine)
            .allowsHitTesting(false)
            .transition(.opacity)
            .task(id: feedback.id) {
                do { try await Task.sleep(for: .seconds(3)) } catch { return }
                guard refreshFeedback?.id == feedback.id else { return }
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.3)) {
                    refreshFeedback = nil
                }
            }
        }
    }

    private func scrollBinding(for feed: HomeViewModel.Feed) -> Binding<ScrollPosition> {
        Binding(
            get: { scrollPositions[feed] ?? ScrollPosition() },
            set: { scrollPositions[feed] = $0 }
        )
    }
}

private struct HomeNodeScrollContainer<Content: View>: View {
    let feeds: [HomeFeed]
    @Binding var selected: HomeFeed
    let state: HomeNodeScrollState
    let content: Content
    let onEdit: () -> Void

    var body: some View {
        GeometryReader { viewport in
            let width = min(720, viewport.size.width)
            header(maxRows: 3, width: width)
                .modifier(HomeNodeRailVisibility(state: state, expanded: true))
                .overlay(alignment: .top) {
                    GeometryReader { expanded in
                        header(maxRows: 1, width: width)
                            .modifier(HomeNodeRailVisibility(state: state, expanded: false))
                            .background {
                                GeometryReader { collapsed in
                                    // The list always has the collapsed viewport's size.
                                    // In expanded mode its extra bottom area is clipped by
                                    // the outer container, not removed by a layout change.
                                    content
                                        // Reserve the clipped bottom area once so the final
                                        // row stays reachable even with an expanded header.
                                        .contentMargins(.bottom, max(0, expanded.size.height - collapsed.size.height),
                                                        for: .scrollContent)
                                        .frame(width: viewport.size.width,
                                               height: max(0, viewport.size.height - collapsed.size.height))
                                        .background(Theme.canvas)
                                        .clipped()
                                        .modifier(HomeNodePageTranslation(
                                            state: state, expandedHeight: expanded.size.height,
                                            collapsedHeight: collapsed.size.height
                                        ))
                                }
                            }
                    }
                }
        }
        // The opaque list clips the expanded rail at its moving top edge. Neither
        // rail, the list viewport, nor safe-area insets change size while scrolling.
        .clipped()
    }

    private func header(maxRows: Int, width: CGFloat) -> some View {
        HStack(alignment: .top, spacing: 4) {
            HomeNodeRail(feeds: feeds, selected: selected, maxRows: maxRows,
                         viewportWidth: max(0, width - 44)) { selected = $0 }
                .equatable()
            Button(action: onEdit) {
                Image(systemName: "slider.horizontal.3").frame(width: 40, height: 40)
            }
            .accessibilityLabel("编辑首页标签")
        }
        .frame(width: width)
        .frame(maxWidth: .infinity)
    }
}

private struct HomeNodeRailVisibility: ViewModifier {
    let state: HomeNodeScrollState
    let expanded: Bool

    func body(content: Content) -> some View {
        let active = expanded ? state.progress < 0.5 : state.progress >= 0.5
        let opacity = expanded ? 1 - state.progress : state.progress
        content
            // Opacity alone leaves the inactive rail's glass at the same location.
            // Move it outside the clip at endpoints without unmounting or changing layout.
            .visualEffect { effect, geometry in
                effect.offset(y: opacity == 0 ? -geometry.size.height : 0)
            }
            .clipped()
            .opacity(opacity)
            .allowsHitTesting(active)
            .accessibilityHidden(!active)
            .transaction { $0.animation = nil }
    }
}

private struct HomeNodePageTranslation: ViewModifier {
    let state: HomeNodeScrollState
    let expandedHeight: CGFloat
    let collapsedHeight: CGFloat

    func body(content: Content) -> some View {
        let translation = expandedHeight - max(0, expandedHeight - collapsedHeight) * state.progress
        return content
            .visualEffect { effect, _ in effect.offset(y: translation) }
            .transaction { $0.animation = nil }
    }
}

private struct HomeNodeRail: View, Equatable {
    let feeds: [HomeFeed]
    let selected: HomeFeed
    let maxRows: Int
    let viewportWidth: CGFloat
    let onSelect: (HomeFeed) -> Void

    static func == (lhs: Self, rhs: Self) -> Bool {
        // Collapse is intentionally not an input. The callback only updates the
        // enclosing bar's stable selection binding; value changes still rebuild both rails.
        lhs.feeds == rhs.feeds && lhs.selected == rhs.selected &&
        lhs.maxRows == rhs.maxRows && lhs.viewportWidth == rhs.viewportWidth
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HomeNodeFlowLayout(
                    viewportWidth: max(0, viewportWidth - Theme.Metric.screenPadding * 2),
                    maxRows: maxRows
                ) {
                    ForEach(feeds, id: \.self) { feed in
                        FilterChip(title: feed.title, isSelected: selected == feed, minimumHeight: 36) {
                            onSelect(feed)
                        }
                        .fixedSize()
                        .accessibilityLabel(feed.title)
                        .id(feed)
                    }
                }
                .padding(.horizontal, Theme.Metric.screenPadding)
                .padding(.vertical, 2)
            }
            // Use intrinsic content height, not a geometry -> @State -> frame feedback loop.
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityHint(maxRows == 1 ? "单行节点列表，可左右滑动" : "最多三行节点列表，可左右滑动")
            .task(id: HomeNodeRailIdentity(feed: selected, feeds: feeds, width: viewportWidth)) {
                await Task.yield()
                guard !Task.isCancelled else { return }
                proxy.scrollTo(selected, anchor: .center)
            }
        }
    }
}

private struct HomeNodeRailIdentity: Hashable {
    let feed: HomeFeed
    let feeds: [HomeFeed]
    let width: CGFloat
}

private struct HomeNodeFlowLayout: Layout {
    let viewportWidth: CGFloat
    let maxRows: Int

    func makeCache(subviews: Subviews) -> HomeNodeLayoutCache {
        HomeNodeLayoutCache(sizes: subviews.map { $0.sizeThatFits(.unspecified) })
    }

    func updateCache(_ cache: inout HomeNodeLayoutCache, subviews: Subviews) -> Void {
        cache.update(sizes: subviews.map { $0.sizeThatFits(.unspecified) })
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout HomeNodeLayoutCache) -> CGSize {
        cache.layout(viewportWidth: viewportWidth, maxRows: maxRows).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout HomeNodeLayoutCache) -> Void {
        let layout = cache.layout(viewportWidth: viewportWidth, maxRows: maxRows)
        for (index, subview) in subviews.enumerated() {
            let frame = layout.frames[index]
            subview.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                          anchor: .topLeading, proposal: ProposedViewSize(frame.size))
        }
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
