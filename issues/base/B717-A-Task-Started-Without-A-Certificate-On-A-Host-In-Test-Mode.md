# B717 — A Task Started Without A Certificate On A Host In Test Mode

```json
{
  "$schema": "issue-1.json",
  "id": "B717",
  "title": "A Task Started Without A Certificate On A Host In Test Mode",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": [],
  "covers": "On a host whose root switched on `start-uncertified`, `porelo task start` starts a task without a certificate after a clear warning and a yes, `--yes` answering it for scripts, and the host accepts such a start only while that switch is on.",
  "openQuestions": 0
}
```

**Why this size.** One verb and one file check in `porelo-host`, one acceptance in its `task start`, the warning and
question in `TaskLaunch`, and their tests.

Decided by the operator on 2026-11-13. A host refuses to start a task without a certificate, since what the task passes
on is then vouched for by nobody. A measuring host has no applied organization and no certifier, so no task can start
there. Agent Machines' role for that host (HO187) sets a test switch, and the start has to honour it. A person's own
flag must never turn the check off on a host that is not in test mode.

## Contract (agreed with Agent Machines, 2026-11-13)

- **The file.** Root's role writes `/etc/porelo-host/test-mode.json` at `up` and removes it at `down`. It must be a
  regular file, not a link, owned by root, mode `0600`, in root-owned directories that neither group nor others can
  write. Its content is `{"v": 1, "switches": ["start-uncertified"], "why": "<text>"}`. A reader acts only on names it
  knows. A file that is missing, of another `v`, unreadable, or failing those checks means no switches, never all of
  them.
- **The question.** `sudo porelo-host test-switches --json` answers `{"switches": [...]}` with the switches that hold
  (`[]` when none), exit 0, and 64 on wrong use. Its sudo rule is the role's. A `porelo-host` that does not know the
  verb counts as no switches.
- **The start at the host.** `porelo-host task start <task>` with empty standard input asks for an uncertified start.
  It is accepted only while `start-uncertified` holds, and refused with 65 otherwise. While it holds, a caller in
  `porelo-people` is accepted for the project `default` whatever the organization says, taken from `SUDO_USER` and
  `SUDO_UID` as now. The record goes under `/var/lib/porelo-host/tasks/<login>/` and says the task is uncertified.
  `task end` removes the token and keeps the record, as for every task. The role removes the account's record
  directory when it deletes the account.
- **The start in porelo.** On a host where a start gets no certificate, porelo asks the host for its switches. Without
  `start-uncertified`, it refuses as today. With it:
  - it prints a warning on standard error: the host is in test mode, so the task starts **without** a certificate and
    what it passes on is vouched for by nobody;
  - at a terminal it asks "Start the task without the check? y/N". `--yes` answers yes. Without a terminal and without
    `--yes`, it refuses as today.
- A start through the daemon is not covered here. It refuses as today, and is filed apart if a measure needs it.

## Plan

- `host`: the file check (each bad kind refused as no switches), the `test-switches` verb, the uncertified
  `task start` and its record, and the man page.
- `apps/app-task` / `HostPerson`: ask for the switches, then the warning, the question and `--yes` in `TaskLaunch`
  where the host's refusal is now, and the empty-input start.
- `doc/running.md` or the host document: one paragraph on test mode, which only root sets.

## Acceptance

- Red first: on a host stand-in with the file holding `start-uncertified`, a start without a certificate and with
  `--yes` starts, and the host's record says uncertified.
- Each bad file (a link, the wrong owner or mode, a writable directory, another `v`, an unknown name only, unreadable)
  gives no switches, and the start is refused as today.
- With the switch on, a terminal start answered `n` is refused and starts nothing. A start with no terminal and no
  `--yes` is refused.
- `porelo-host task start` with empty input is refused with 65 without the switch, whatever porelo sent.
- The measuring VM run (HO186) starts its task with `--no-input --yes`.
