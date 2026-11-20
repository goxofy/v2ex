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

On device, refresh R2, leave the app running while reading topics, then refresh again later without force-quitting. Check for updated website ranking or a visible refresh error above the retained list. A full IPA build and device UI validation are still required; the standalone tests do not exercise SwiftUI's refresh gesture or prove the live site's cache behavior.

## Website notification read state

```sh
python3 tests/check-notification-sync.py
```

Uses the production counter parser, request sequence and view model with an isolated transport. Verifies account checks before marking, homepage-only refresh, website counter import, acknowledgement followed by verification, failure preservation, missing sessions, and stale results after logout. No real notification is changed by this test. V2EX exposes an account-wide unread counter; the app does not invent individual read flags or persist local seen IDs.
