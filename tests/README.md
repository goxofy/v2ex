# Regression checks

## Reply submission

```sh
python3 tests/check-reply-submission.py
```

Requires macOS 14+ with a Swift 5.9+ toolchain. Runs `ReplySubmissionTests` and `NodeFollowingTests` in a temporary Swift package, copying the production networking, HTML parser, and models without modifying their implementation. This lets networking tests run on Xcode versions that cannot build the app's iOS 26 `FoundationModels` dependency. It does not replace a full iOS build or UI test.

Covers form-scoped tokens, form encoding, cookie/header consistency, explicit server errors, authentication/challenges, pagination, new-reply confirmation, unconfirmed outcomes, and at most one POST per action. All HTTP responses are local URLProtocol fixtures; no real replies are posted. The tests also belong to the app's normal XCTest target. For an end-to-end check, use a reply you intended to post, confirm it appears on the website, and check that the app clears the draft only after confirmation. An unconfirmed result must preserve the draft and offer a website link rather than encourage immediate resubmission.

## Moderation

Run from the repository root:

```sh
python3 tests/check-member-block.py
python3 tests/check-block-store.py
```

These compile the production parser/request flow and store declarations with isolated transport/persistence adapters. They do not use a real account or change a website blacklist. Coverage includes missing user profiles (404), distinguishing failures from an empty list, confirmation before changing state, ignoring legacy local rules, login requirements, account-specific caches, and late responses after an account switch.

## Simulator response replay

Debug simulator builds support `-moderationReplay /absolute/path/scenario.json`. This opens the moderation screen and runs the normal list-reading flow against captured HTTP response bodies. It supplies an in-memory test session, does not write Keychain credentials, and intercepts all V2EX client requests. Requests without captured responses fail with 503, so no block/unblock request reaches the real website. This code is excluded from device and Release builds.

The scenario format is:

```json
{
  "account": "fixture-account",
  "responses": [
    {"path": "/", "status": 200, "body": "<script>const blocked = [7,42];</script>"},
    {"path": "/api/members/show.json?id=7", "status": 200, "body": "{\"id\":7,\"username\":\"example\"}"},
    {"path": "/api/members/show.json?id=42", "status": 404, "body": "{\"status\":\"error\",\"message\":\"Object Not Found\"}"}
  ]
}
```

Keep personal captures outside the repository and omit credentials. The homepage fixture can retain only the `blocked` script needed by the parser. Response replay verifies UI and parsing; it is not a live authenticated website test.

## Feed pagination

```sh
python3 tests/check-feed-pagination.py
python3 tests/check-topic-pages.py
```

Checks cover append/deduplication, retrying the same page after failure, refresh, cached cursors, stale responses after switching feeds, following pagination/session invalidation, public recent/node HTML templates, and end-of-list detection. The parser check optionally accepts paths to public page-2 HTML captures (include `node` in node capture filenames); captures stay outside the repository.

### iOS public-feed refresh

`python3 tests/check-reply-submission.py` also runs `FollowingTests` against the production client and `HomeViewModel`. Public-feed cases verify unique cache-busting URLs, explicit cache bypass headers/policy, preservation of tab/page parameters, repeated R2 refresh within one model, and pagination reset after refreshing All. Failed refreshes retain previous rows and an error; a subsequent successful refresh replaces rows and clears the error. These use URLProtocol fixtures rather than waiting several hours or accessing a real account.

Delayed URLProtocol responses also cover an ordinary same-feed load during refresh or pagination, switching away before a response arrives, a manual refresh superseding pagination, stale refresh callbacks, duplicate manual refreshes, cancellation/retry, and success timestamps. These demonstrate the model race independently of the live website: a same-feed cache read must not invalidate an active refresh. Row comparisons include fields and ordering, not only topic IDs.

Manual refresh runs in an independent main-actor task and the caller awaits completion. Cancelling SwiftUI's pull-to-refresh callback must not cancel that request, including when the callback was already cancelled before entering the model. Tests cover caller cancellation before/during the request, a concurrent button action, feed/account changes, and transport cancellation. Ordinary load-task cancellation and real transport cancellation still retain old rows without recording success; stale responses still cannot cross feed/account boundaries. This tests the cancellation boundary, not why SwiftUI cancels a callback on a particular device.

On device, pull to refresh R2, open a topic and return, switch tabs and return, background/foreground the app, then pull again after extended use without force-quitting. Refresh results and the last successful fetch time appear only in a non-interactive overlay for three seconds, then fade out over 0.3 seconds (no animation with Reduce Motion). They no longer reserve space above the rows. Cache reads, failures, cancellations and discarded responses must not advance the successful-fetch time. On a failed or cancelled refresh, check that the persistent error card still offers Retry for both empty and retained lists after the toast disappears. A successful identical response is labeled as unchanged; it is not a claim about when the website itself last updated. The presentation change does not alter the refresh task or cancellation boundary; the existing concurrent manual-action regressions remain in place.

Feedback tests cover changed/unchanged responses, coalesced loading transitions, unchanged cached state, idle/loading suppression, failure timestamps, failure/cancellation retry without an error string, success clearing the retry fallback, and distinct timer identities. On device, also refresh twice within three seconds, switch feeds during a toast, and open a topic during a request: stale timers must not dismiss newer feedback, and feedback must not reappear on returning from another page. The standalone tests verify feedback derivation, not SwiftUI timers or animations.

### Compact, scroll-responsive home tabs

Both platforms use natural-width flow layout with at most three rows. Configuration order is read left-to-right within each row, then top-to-bottom, without sorting or changing the saved configuration. Chips keep their full titles and existing selected colors. Minimum chip height is 36pt/dp, with 2pt/dp row spacing and 2pt/dp padding above/below; three normal rows occupy 116pt/dp instead of the previous 144pt iOS / 154dp Android grid. Fewer chips can use fewer rows. If the content cannot fit three rows at the viewport width, the scrollable canvas widens just enough to fit them without squeezing labels. A single label wider than the viewport remains horizontally scrollable rather than truncated.

Scrolling the topic list more than 120pt/dp from the top collapses the tabs to one horizontal row; returning within 8pt/dp of the top expands them. Android also collapses once the first list item is fully offscreen. The different thresholds avoid oscillation around the top. iOS ignores geometry changes caused by header/safe-area resizing, and its hysteresis gap exceeds the height released by two rows. Height transitions take approximately 220ms (iOS disables animation with Reduce Motion; Android honors the system animation scale). Selection is brought into view after reflow. Each feed retains its scroll/collapse state; hidden pages must not drive the active header. iOS observes the selected feed's scroll view, including Hacker News, without altering refresh callbacks.

Swift and Android unit tests cover natural widths, row-major order, all 15 chips, three-row overflow, long titles, single-row collapse, sparse/empty input, variable heights, invalid metrics and threshold hysteresis. Swift additionally checks viewport/inset resize suppression. These geometry tests and local type checks do not replace device validation of scroll anchoring and animation.

The iOS header now uses intrinsic scroll-content height instead of feeding measured height back into HomeView state and starting a second animation. Natural chip sizes and both row arrangements are cached through SwiftUI's Layout cache lifecycle; width/content changes invalidate the cache, while identical updateCache calls preserve it. A separate header view owns width observations so these do not rebuild the topic-list body. The original 220ms transition, raw geometry/inset checks, thresholds and per-feed state remain intact. Unit tests demonstrate only two state changes during a 2,002-sample scroll up/down; the original scroll callback already had this deduplication, so it was not writing collapse state every frame.

Android does not have the iOS height-observation feedback loop. It now caches row arrangements across layout passes and derives the collapse Boolean inside snapshotFlow, rather than notifying the parent for each pixel. Tests cover cache reuse/invalidation and actual snapshotFlow emissions across pixel-by-pixel scrolling.

For an isolated old/new iOS header comparison (requires the `db315b9` baseline commit and an iOS 18+ simulator SDK):

```sh
python3 tests/check-home-node-presentation.py
# Optional: provide a booted disposable simulator to run both variants.
python3 tests/check-home-node-presentation.py --device <simulator-uuid>
```

The script extracts production header/layout expressions, uses stub feed/theme/chip dependencies and synthetic topic rows, and builds a temporary simulator app. It never runs the full application or accesses an account. With `--device`, it installs a test-only bundle, runs ten 220ms transitions per variant after warm-up, checks equal endpoint heights and actual intermediate animated heights, then uninstalls the bundle. Use a disposable simulator without another copy of that test bundle. It neither boots nor deletes the supplied simulator.

On the local iOS 18.3 simulator, the final comparison measured 326 vs 1 arrangement calculations, 4,890 vs 150 chip measurements, 20 vs 10 parent body evaluations and 10 vs 0 height-state writes (old vs new). The expanded arrangement was warmed up before counting; only the first collapsed arrangement needed solving in the cached variant. Both retained 116pt/40pt endpoint heights and animated intermediate frames. Counts vary by simulator scheduling. This verifies removal of redundant work, not device FPS, iOS 26 safeAreaBar behavior, or absence of gesture-time frame drops. Device validation remains necessary.

On device, check small/large phones, landscape, larger text, one tab, all 15 tabs, `问与答`, mixed long/short names, and a title wider than the screen. Edit/reorder/remove the selected tab, swipe between feeds at different list offsets, and open a topic then return. Scroll slowly through the collapse threshold, reverse direction during animation, fling upward/downward, tap the status bar to return to the top, and pull to refresh. Ensure there is no repeated collapse/expand loop, row overlap or title truncation, selection stays reachable, and the edit button remains visible. The confirmed iOS refresh toast and cancellation isolation are unchanged. Android retains its existing error and offline notices.

Local OSLog events in subsystem `com.vibe.v2ex`, category `feed-refresh`, correlate refresh source, HTTP start/status, parsed count, and commit/discard outcome using request IDs. They contain no account data, cookies, tokens or response bodies, and are not uploaded. A full IPA build and device UI validation are still required; the standalone tests do not exercise SwiftUI's refresh gesture or reproduce the reported hours-long behavior.

## Website notification read state

```sh
python3 tests/check-notification-sync.py
```

Uses the production counter parser, request sequence and view model with an isolated transport. Verifies account checks before marking, homepage-only refresh, website counter import, acknowledgement followed by verification, failure preservation, missing sessions, and stale results after logout. No real notification is changed by this test. V2EX exposes an account-wide unread counter; the app does not invent individual read flags or persist local seen IDs.
