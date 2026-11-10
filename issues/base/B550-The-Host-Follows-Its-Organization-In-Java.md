# B550 — The Host Follows Its Organization In Java

```json
{
  "$schema": "issue-1.json",
  "id": "B550",
  "title": "The Host Follows Its Organization In Java",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 34,
  "blockedBy": ["B546", "B547"],
  "covers": "`porelo-host org follow | refresh | renew-pin`; the project verbs called in-process."
}
```

Review since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.

**What must be true.** The follow (timer), refresh (window over root's ssh; the admin's central token on stdin only for a waiting
migration) and renew-pin, as `porelo-host-person org ...`, calling [B545](README.md#b545) and [B546](README.md#b546) in-process instead of a subprocess.

## Why

Step 7, the follow; the biggest single piece (with [B551](README.md#b551) about 2800 lines of Python). The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, on [B546](README.md#b546); reviewed by Agent Core.
- **The verbs:** `porelo-host org follow`, `org refresh` and `org renew-pin`, run as root by `porelo-host-follow`, answering as `porelo-host-person` does. New classes: `HostFollow`, `FollowContext`, `FollowPeople`, `FollowState`, `FollowProjects`, `FollowPushes`, `FollowRooms`; the project work runs in process through [B546](README.md#b546)'s classes, not as a child.
- **Deadlines:** one 120-second measurement budget for all projects, as the Python's, inside the unit's 300 seconds. A child's own timeout is clipped by every enclosing deadline; "no deadline" never escapes one. Inventories and bundles are streamed without a byte cap, as in the Python.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - `forge.conf` is read for `name=`, which the role writes (it was `domain=`, and a fixture had been bent to match);
  - a failed push-config removal no longer spares the later members, and a stopped account's shell and room cleanup needs no session bus;
  - a purge that lifts expiry for a moment never records the account as locked until expiry and the manager's stop are back;
  - a child's timeout is recorded as a step failure and the follow goes on;
  - the subordinate-ID files keep their modes;
  - a standalone measurement bounds its reader lookup at the Python's 60 seconds;
  - an inverted assertion is set back to the Python test's.
  - Refuted with reasons: a repository's Git failure erasing every row (the Python answers that repository `cannot-say`).
- **Tests.** 160 follow, 14 person and 3 card tests of the Python are paired. `host` (766), `organization/git-tree` (715) and `wire` (146) ran whole outside a sandbox. `HttpForgeAdminTest.aRepeatedPageWithReorderedFieldsIsRefused` failed once under load and passed three times alone.
- **Not verified here:** real root, accounts, Forgejo, Matrix, podman, systemd and the VM.
