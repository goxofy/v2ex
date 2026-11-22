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

iOS uses continuous scroll-driven progress instead of threshold switching. Every 160pt of forward/backward topic-list scrolling moves the header through its full collapse/expansion range, clamped to 0...1. Reverse scrolling reveals it immediately even far down a feed; this is accumulated scroll displacement, not an absolute distance-from-top mapping. Holding a finger still leaves progress unchanged. During dragging and deceleration there is no independent animation timeline. On the idle scroll phase, a partial header snaps to the nearer endpoint over about 120ms (below 0.5 expands; 0.5 or above collapses). Touching again interrupts the snap at its current displayed progress. Reduce Motion makes the idle snap immediate. Offset zero or pull overscroll always fully expands and cancels any snap.

Each feed retains independent progress and scroll state; hidden pages cannot drive the active header. Feed switches and leaving Home cancel pending snaps; activating a feed again settles any retained partial progress. Rotation/inset changes rebase the offset sample without driving progress; invalid samples are ignored. iOS observes the selected feed's scroll view, including Hacker News. The refresh callback, cancellation boundary, and toast derivation/timer are unchanged; the toast remains a non-interactive overlay above the topic rows.

Both fixed-row rails remain mounted with independent horizontal positions. Natural chip sizes and arrangements use the existing Layout caches. Selection, configuration and viewport-width changes update/reposition both rails; progress never triggers scrollTo. GeometryReader supplies static widths and intrinsic rail heights without measurement-to-state writes. The list always uses the collapsed viewport's fixed size. A visualEffect translation moves it down for the expanded header; the outer container clips the extra bottom area, and the opaque list clips the lower expanded rows at its moving top edge. A fixed bottom content margin reserves that clipped area so the final topic remains reachable even when fully expanded. Rail opacity follows progress. There is no changing height proposal, safe-area inset, layout animation, or chip reflow during scrolling or snapping. Only the small presentation modifiers observe progress, not HomeView or the topic rows. The inactive rail is excluded from hit testing and accessibility.

Android is unchanged: it retains 120dp/8dp hysteresis, approximately 220ms transitions, cached arrangements and snapshotFlow Boolean deduplication. Its first-visible-item rule and system animation-scale behavior also remain intact. This iOS experiment is a single commit on top of `eef708c`; reverting only that experiment commit restores the previous two-state implementation and its tests.

Swift and Android geometry tests still cover natural widths, configuration order, all 15 chips, overflow, long titles, sparse input, variable heights and cache invalidation. iOS state tests now cover per-pixel continuous progress, reverse scrolling deep in a feed, top/overscroll, resize rebasing, invalid samples, stationary holds, nearest-endpoint snapping, Reduce Motion, snap interruption and independent feeds. Android retains its threshold tests.

For an isolated production iOS header check (requires an iOS 18+ simulator SDK):

```sh
python3 tests/check-home-node-presentation.py
# Optional: provide a booted disposable simulator to exercise scrolling and snapping.
python3 tests/check-home-node-presentation.py --device <simulator-uuid>
```

The script extracts the production container, rail/layout expressions and state implementation, uses stub feed/theme/chip dependencies and synthetic topic rows inside a TabView, and builds a temporary simulator app. It never runs the full application or accesses an account. With `--device`, it drives the actual UIScrollView offsets forward/backward in 4pt steps, verifies progress and visual translation after each sample, holds midway, tests idle snaps and interruption, and checks that viewport size/insets and both rails' content geometry/offsets remain fixed. It also verifies topic hit testing, selection at the end, reordered tabs, width changes, returning to the top, a single-tab configuration and final-row visibility with an expanded presentation at the bottom limit. It uninstalls the test bundle afterwards. Use a disposable simulator without another copy of that test bundle; the script neither boots nor deletes the supplied simulator.

On the local iOS 18.3 simulator, after warm-up, 80 forward/reverse samples plus holds and snaps produced zero arrangement solves, chip measurements, chip placement passes, parent body evaluations and scrollTo requests. The normal header visually occupied 116pt down to 40pt while the list viewport stayed fixed. An initial ordinary offset modifier caused fractional-pixel viewport size changes; visualEffect translation removed those changes. These checks verify fixed geometry and bounded work, not device FPS or iOS 26 navigation/gesture behavior. The fixture drives offsets and explicitly exercises idle snap logic, not a physical finger gesture. A full app build and device validation remain necessary.

On device, check small/large phones, landscape, larger text, one tab, all 15 tabs, `问与答`, mixed long/short names, and a title wider than the screen. Edit/reorder/remove the selected tab, swipe between feeds at different list offsets, and open a topic then return. Slowly scroll a few pixels, hold without lifting, reverse direction while deep in the feed, stop on either side of halfway, touch again during a snap, and fling up/down. Tap the status bar to return to the top and pull to refresh: the header must be fully expanded at the top without affecting refresh cancellation isolation. Check the bottommost topics and pagination with both expanded and collapsed headers, since the fixed oversized viewport is clipped rather than resized. Ensure no row overlap, blank strip or title truncation; selection and the edit control must remain reachable. Horizontal rail positions must survive progress changes. Check Reduce Motion and VoiceOver: only the active rail should receive touches or accessibility focus. The confirmed refresh toast remains above the rows. Android retains its existing behavior.

Local OSLog events in subsystem `com.vibe.v2ex`, category `feed-refresh`, correlate refresh source, HTTP start/status, parsed count, and commit/discard outcome using request IDs. They contain no account data, cookies, tokens or response bodies, and are not uploaded. A full IPA build and device UI validation are still required; the standalone tests do not exercise SwiftUI's refresh gesture or reproduce the reported hours-long behavior.

## Website notification read state

```sh
python3 tests/check-notification-sync.py
```

Uses the production counter parser, request sequence and view model with an isolated transport. Verifies account checks before marking, homepage-only refresh, website counter import, acknowledgement followed by verification, failure preservation, missing sessions, and stale results after logout. No real notification is changed by this test. V2EX exposes an account-wide unread counter; the app does not invent individual read flags or persist local seen IDs.
