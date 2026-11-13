# B753 — The Task Profile Connects Only To Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "B753",
  "title": "The Task Profile Connects Only To Porelo",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 52,
  "blockedBy": ["B752"],
  "covers": "`porelo-task` holds one unix connect rule, to a peer labelled `porelo` or `porelo-task`, under `abi <abi/5.0>`, and the package check holds the packaged profile to it.",
  "openQuestions": 0
}
```

**Why this size.** S: one profile file, its comments, and `AppArmorProfilesTest` in `dist/package-check`. Estimate: 45
minutes (Core's median for S).

**What must be true.** The task's profile says, in one rule the kernel enforces for abstract sockets, that a task
connects only to Porelo.

Split from [B175](README.md#b175)'s step 9. On walk4 the candidate under `abi <abi/5.0>` with `unix (connect)
peer=(label=porelo)` refused the stack `porelo//&unconfined` and plain `unconfined`, and allowed a binder that wears
plain `porelo`. The packaged profile is still written under `abi <abi/4.0>`.

## Plan

- `apparmor/porelo-task`: `abi <abi/5.0>`, and one connect rule `unix (connect) peer=(label=porelo),` beside
  `peer=(label=porelo-task)` for the task's own sockets. Every other unix connect is refused. Each rule's comment says what it is for and that a path socket is
  held by file rules, so the lock is for the abstract names of [B752](README.md#b752).
- `AppArmorProfiles` and `AppArmorProfilesTest` (`dist/package-check`): the packaged profile has the abi line and
  the one connect rule with its peers, and no `change_profile`, `px` or `cx`.
- First red: `AppArmorProfilesTest` against today's profile, which has `abi <abi/4.0>`.

## Acceptance

- `AppArmorProfilesTest` holds the abi line and the connect rule, and failed against the profile before.
- `apparmor_parser -Q` compiles the profile on Ubuntu 26.04.
