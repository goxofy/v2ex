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

### Three-row home tabs

Both platforms use a three-row horizontal grid, preserving configured item order (top-to-bottom, then the next column) and existing selected-chip colors. Overflow remains horizontally scrollable; changing the selected page scrolls its chip into view. Long labels truncate visually while retaining their full accessibility text. Check small/large phones, landscape, larger text, one tab, all 15 tabs, long node names, editing/reordering/removing the selected tab, and swiping between feeds. Ensure the edit control remains reachable and topic rows remain scrollable below the grid. Android retains its existing error and offline notices; it has no persistent success-status block to replace with a toast.

Local OSLog events in subsystem `com.vibe.v2ex`, category `feed-refresh`, correlate refresh source, HTTP start/status, parsed count, and commit/discard outcome using request IDs. They contain no account data, cookies, tokens or response bodies, and are not uploaded. A full IPA build and device UI validation are still required; the standalone tests do not exercise SwiftUI's refresh gesture or reproduce the reported hours-long behavior.

## Website notification read state

```sh
python3 tests/check-notification-sync.py
```

Uses the production counter parser, request sequence and view model with an isolated transport. Verifies account checks before marking, homepage-only refresh, website counter import, acknowledgement followed by verification, failure preservation, missing sessions, and stale results after logout. No real notification is changed by this test. V2EX exposes an account-wide unread counter; the app does not invent individual read flags or persist local seen IDs.
