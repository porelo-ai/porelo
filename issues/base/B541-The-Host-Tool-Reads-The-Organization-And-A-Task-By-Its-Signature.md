# B541 — The Host Tool Reads The Organization And A Task By Its Signature

```json
{
  "$schema": "issue-1.json",
  "id": "B541",
  "title": "The Host Tool Reads The Organization And A Task By Its Signature",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 41,
  "blockedBy": ["B540"],
  "covers": "`porelo-host org status` and `task show <fingerprint>`, read-only, answering as the Python does."
}
```

Review since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.

**What must be true.** `porelo-host org status [--json]` (root's read of host, organization, persons, projects, repositories and their
central state) and `porelo-host task show <fingerprint>` (through `sudo -n`, tasks in `SUDO_USER`'s projects) answer as
`porelo-host-person org status` and `task --show` do today, compared on the rehearsal VM; the organization card's and
the records' readers move down from `apps/app-*` into their libraries where `host/` needs them.

## Why

Step 1; proves card and record reading before anything writes. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, reviewed by Agent Core.
- **`org status [--json]`** reads, under a shared `flock` on `/etc/porelo-host/forge.conf`, what the Python's `org_status` reads:
  - the forge configuration and pin;
  - the applied organization and the follow's last refusal;
  - `people/`, the purged list, each project's progress and its last central measurement.
  It answers the same JSON keys, values and order, and refuses (65) when `SUDO_USER` is set. Without `--json` it prints one line: `<host>: <organization state>; <n> persons; <n> projects`.
- **`task show <fingerprint>`** takes the person from `SUDO_USER` alone, or serves root's units when it is absent, under the exclusive lock the Python takes. It searches every person's task records and serves a sudo caller only a record whose principal names a project they are an unlocked member of. Membership is read from `/etc/porelo-host/people/<login>.conf`. It refuses a person who is not admitted and unlocked or is revoked (65), and a malformed fingerprint (64, the Python's words).
- **Root's files** go through `HostFiles` on `RootOwnedFile`: root's, not writable by group or others, no symlink, every parent root's, bounded and strict UTF-8.
- **The lock** is Linux `flock` through three FFM downcalls, so it is compatible with the Python's readers and writers while both run. The native build passed `ffm-check`: 3 downcalls registered.
- **`wire`:** `RootOwnedFile` gains a public attribute reader, so `host/`'s fixtures still go through the real checks. `Json.parseNumberTypes` keeps integers apart from whole-valued floats, as the Python's JSON does.
- **Where it answers differently from the Python:**
  - Through sudo, `task show` limits reads to the caller's projects and refuses a person the organization names revoked; the Python's `show` answers any admitted person any task without asking about revocation.
  - Bounds: 16 MiB per file, no duplicate keys, no non-finite numbers.
  - Only canonical dates are accepted.
  - A malformed nested shape answers 73 where the Python crashed.
  - No EINTR retry on `flock`.
- **Tests:** `HostOrgStatusTest`, `HostTaskShowTest`, `HostReadRefusalsTest`, `HostConfigurationLockTest`, and new cases in `JsonTest`. Agent Core saw these go red with their checks broken: sudo refused for `org status`, the admission check, the group/other-writable check, and the fingerprint pattern.
- **After Agent Machines' domain reading:** `task show` serves root's units without `SUDO_USER` (any task's record) as the Python's `task --show` does. Through sudo it serves only a recorded, admitted person, refused with 65 otherwise: a locked or pending person, or a valid account in no organization. Both were seen red first. `org status` already matched the Python: root only, 65 through sudo.
- **Rule decided by Agent Machines:** through sudo, a recorded, unlocked person can read any member's task when its principal (`task:<project>:<task>`) names a project the caller belongs to. A task outside those projects is refused with 65 and byte-for-byte the same stdout and stderr as an unrecorded fingerprint. A person the organization names revoked remains refused with 65. Root's units without `SUDO_USER` can read any record. This lets commit checks trace signatures on other members' commits in shared projects.
- **Not verified here:** the comparison against the Python on the rehearsal VM, with a real sudo and NSS.

