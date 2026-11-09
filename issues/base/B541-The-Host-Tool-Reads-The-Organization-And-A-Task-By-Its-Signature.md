# [B541](README.md#b541) — The Host Tool Reads The Organization And A Task By Its Signature

**Status:** Review, since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.
**Size:** L.
**Blocked by:** [B540](README.md#b540).

**What must be true.** `porelo-host org status [--json]` (root's read of host, organization, persons, projects, repositories and their
central state) and `porelo-host task show <fingerprint>` (through `sudo -n`, `SUDO_USER`'s tasks only) answer as
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
- **`task show <fingerprint>`** takes the person from `SUDO_USER` alone and reads only that person's task directory, under the exclusive lock the Python takes. It refuses (65) a person who is not admitted and unlocked, another person's task, and a malformed fingerprint (64, the Python's words).
- **Root's files** go through `HostFiles` on `RootOwnedFile`: root's, not writable by group or others, no symlink, every parent root's, bounded and strict UTF-8.
- **The lock** is Linux `flock` through three FFM downcalls, so it is compatible with the Python's readers and writers while both run. The native build passed `ffm-check`: 3 downcalls registered.
- **`wire`:** `RootOwnedFile` gains a public attribute reader, so `host/`'s fixtures still go through the real checks. `Json.parseNumberTypes` keeps integers apart from whole-valued floats, as the Python's JSON does.
- **Where it answers differently from the Python:**
  - `task show` no longer serves root without sudo, nor searches every person's records. An unrelated person's broken record therefore no longer blocks a lookup.
  - Bounds: 16 MiB per file, no duplicate keys, no non-finite numbers.
  - Only canonical dates are accepted.
  - A malformed nested shape answers 73 where the Python crashed.
  - No EINTR retry on `flock`.
- **Tests:** `HostOrgStatusTest`, `HostTaskShowTest`, `HostReadRefusalsTest`, `HostConfigurationLockTest`, and new cases in `JsonTest`. Agent Core saw these go red with their checks broken: sudo refused for `org status`, the admission check, the group/other-writable check, and the fingerprint pattern.
- **Not verified here:** the comparison against the Python on the rehearsal VM, with a real sudo and NSS.

