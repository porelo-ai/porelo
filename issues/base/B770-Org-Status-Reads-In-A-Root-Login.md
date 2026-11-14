# B770 — Org Status Reads in a Root Login

```json
{
  "$schema": "issue-1.json",
  "id": "B770",
  "title": "Org Status Reads in a Root Login",
  "state": "Backlog",
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
