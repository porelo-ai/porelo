# B770 — Org Status Reads in a Root Login

```json
{
  "$schema": "issue-1.json",
  "id": "B770",
  "title": "Org Status Reads in a Root Login",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 127,
  "blockedBy": [],
  "covers": "`porelo-host org status` answers in an ordinary root login over ssh, whose session variables it neither needs nor passes on",
  "openQuestions": 0
}
```

**Why this size.** S: one verb's way past one check in `host/`, with its tests and a line in the host docs. Estimate:
38 minutes (Core's median for S).

**What must be true.** An administrator who logs in as root over ssh and runs `porelo-host org status` reads the
host's state. A variable of that login session never makes it unreadable.

Filed 2026-11-14 from the operator's live test on a rented host (porelo 0.5.1~snapshot.543, ubuntu 26.04), under ST361.

## Why

Seen: `ssh root@host`, then `porelo-host org status` exits 78 with `porelo-host: refusing environment variable
DBUS_SESSION_BUS_ADDRESS; broken host environment (check sudo env_keep or the unit environment)`.

`PoreloHost` calls `HostEnvironment.check(environment)` first, for every verb. It refuses any name outside a fixed list
of what sudo and root's units carry. A root login shell carries more: `DBUS_SESSION_BUS_ADDRESS`, `SSH_CONNECTION`,
`SSH_CLIENT`, `SSH_TTY`, `XDG_SESSION_ID`, `XDG_RUNTIME_DIR`, `PWD`, `OLDPWD`, `SHLVL`, `_` and others. The names are
checked in sorted order, so DBUS is only the first one named. Yet `OrgStatus` refuses to run through sudo
(`org status is root's alone and never runs through sudo`), so a root login is the one way left to call it, and that
way is refused too. Today no interactive caller can read the status at all.

The check guards the boundary of sudo and root's units: a variable that reaches a child or moves a path. `org status`
reads fixed files under `/etc/porelo-host` under a read lock and starts no child.

## Plan

- `PoreloHost`: for exactly `org status` (with or without `--json`), with no `SUDO_*` variable, skip
  `HostEnvironment.check`; every other verb keeps it. `OrgStatus` keeps its sudo refusal.
- Check in `OrgStatus` that it reads no environment variable, beyond the `SUDO_USER` check, and starts no child.
- `HostEnvironmentTest` / `PoreloHostTest`: a root login's environment (the list above) lets `org status` answer and
  still refuses `org follow` and `project add`. Red first: today `org status` exits 78.
- The host docs name `ssh root@host porelo-host org status` as the way to read it.

## Built

- `PoreloHost.readsTheStatusAsRoot(args, environment)`: true for exactly `org status` and `org status --json` with no
  `SUDO_*` variable; `run` skips `HostEnvironment.check` for it alone. `OrgStatus` reads no variable but `SUDO_USER`,
  which it refuses, and starts no child, so nothing the check guards is reached.
- `host/README.md` names `ssh root@host porelo-host org status`; changelog `org-status-in-a-root-login.yml`.

Tests (`HostOrgStatusTest`): a root login's environment (`DBUS_SESSION_BUS_ADDRESS`, `SSH_CONNECTION`, `SSH_TTY`,
`XDG_SESSION_ID`, `SHLVL`, `_`) reads the status in both forms; the same environment still refuses `org follow`
with 78 for the environment; with `SUDO_USER` added the status is still refused. Seen failing first: the root login's
status answered 78.

### Reading (Sonnet sub-agent, one reading)

No Red. Yellow, fixed: the sudo test asserted only a non-zero exit, so a sudo refusal (65) would have passed; it
asserts 78 and the environment's words now. Added: other argument lists (`--json extra`, `--help`, `--json=x`, `-h`)
and a lone `SUDO_UID` or `SUDO_TTY` keep the check. The README says the exemption needs root and no `SUDO_*`.
Yellow, left: a non-root caller with a session's variables now gets 77 (root's alone) instead of 78; the read
fixture runs as root only, so it is not tested here.

## Red-first test

`PoreloHostTest`: `org status` with root's uid and an environment that holds `DBUS_SESSION_BUS_ADDRESS` and
`SSH_CONNECTION` answers the status (exit 0); today it exits 78.

## Risks

- A verb list beside the check can grow by habit. It stays the one read-only verb, named in a comment and pinned by
  the test that another verb is still refused.

## Blockers

None.

## Acceptance

- In a root ssh login on a host, `porelo-host org status` answers, and `org follow` from the same shell is still
  refused for the environment.

## Open questions

None.
