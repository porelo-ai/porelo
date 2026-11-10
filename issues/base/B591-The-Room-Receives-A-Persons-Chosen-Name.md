# [B591](README.md#b591) — The Room Receives A Person's Chosen Name

**Status:** Implementing, since 2026-11-10. Filed 2026-11-10 under ST290, from the fourth move.
**Size:** M - include names in the organization fetch result and carry admitted persons' names through the host follow to the transport input.
**Blocked by:** [B550](README.md#b550).

**What must be true.** The host passes each admitted person's chosen name from their organization card to the
project room, so a person without a name there is shown under it from their first message. A name they later set in
their Matrix client stays theirs; names never grant admission or change which login owns an account.

## Plan

Read in the code: `apps/app-messaging`'s `TransportLifecycle.persons` resolves admitted logins for hearing messages;
[B519](README.md#b519) fixes that admission and [B583](README.md#b583) reads room members, neither passes chosen
names to `keep-room`. That call is in `host/`'s `FollowProjects.reconcile`, through `FollowRooms.room`: its arguments
carry `--persons`, and stdin carries only `secrets.PORELO_MATRIX_ADMIN_TOKEN`. `OrganizationQuestions.Person` already
has `name`, but `organization/git-tree`'s `GitTreeOffline.persons` omits it from the host's fetch result.

- Add the verified card's `name` to `GitTreeOffline.persons`' result and hold it in the `FollowState` applied person
  row. Keep older rows without a name readable; never manufacture a chosen name from the login. Test the organization
  result through its offline contract as well as the host's consumption of it.
- In `FollowProjects.reconcile`, pair the applied persons' names with exactly the logins `FollowPeople.readerLogins`
  admits after card locks. Extend `FollowRooms.room` to pass these as
  `{"settings":{"names":{"ada":"Ada"}},"secrets":{...}}` on stdin for `keep-room`; `--persons` stays the admitted
  login list. Use JSON serialization, so commas and equals signs remain part of one person's name, never separators.
- Build this against the transport contract: every names key is in `--persons`; a display name is 1 to 64 characters,
  without control or format characters, `@` or `:`. A missing name keeps the transport's existing behavior. A named
  missing account is made with the name, an existing unnamed account is named, and any existing nonempty name is kept
  by the transport. The host reports a refused name without echoing its value and does not silently drop membership
  reconciliation or retry a permanently invalid name as a transient error.
- Red first: `HostFollowProjectsTest` captures stdin of `keep-room` for an admitted person with a chosen name: the
  settings map has that login and name beside the existing secrets. Cover a name with comma and equals, a locked or
  revoked person absent from both inputs, missing-name compatibility and a transport name refusal. The offline
  contract test first fails because the person row lacks the card's name.
- Document the host's source of names and the transport's refusal behavior in `doc/messages.md`. Run organization
  checks with `-Pwhole` and the real-room acceptance on the installed transport that accepts `settings.names`.

## Acceptance

- On a host, a person whose organization card supplies a valid name shows under it at first sign-in and in room
  membership. A later name set in their client survives another follow; the test fails if another login gets it.
- The captured input contains names only for admitted persons, no name enters an argument, and a transport refusal
  is reported without exposing the supplied name or administrator credential.

## To be checked

- Confirm the installed transport advertises or documents the `settings.names` contract before machine acceptance;
  measure the host's permanent-refusal handling for an invalid display name without treating a name as admission.
