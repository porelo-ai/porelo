# B613 — A Stale Organization Read Says Why

```json
{
  "$schema": "issue-1.json",
  "id": "B613",
  "title": "A Stale Organization Read Says Why",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "`org` and `project show` answer `stale_why` beside `stale`: a named cause and git's own words; a project's repositories in `cannot-say` carry a `why`."
}
```

**Why this size.** the organization's git store keeps git's words and a named cause on what it throws, the display
refresh keeps them in its receipt, two commands and the project's repositories answer them, the contract page; unit
tests over the existing display fixture.

Filed 2026-11-11 from an operator test.

**What must be true.** The interface shows "Its repositories could not be read just now" and no reason. `porelo
project show <p> --json` answers `"stale": true` and repositories `{"state": "cannot-say"}` with no `why`, and
`porelo org --json` answers stale with nothing beside it. A person cannot tell an unreachable forge from a refused key,
an untrusted host key, no network, or a display that only showed the kept state while a check runs.

How it is today, read in the code:

- `TreeRepository.mainAt` throws `Unreachable` without git's standard error; `fill` keeps git's own lines in the
  message only.
- `GitTreeStore` turns a `CommandException` into "did not answer in time".
- `OrgAccess.fetched` catches `Unreachable` and answers the last trusted state with nothing of why.
- `OrgReading.display` without `--refresh` always answers the kept state; the detached `org refresh-view` records
  only `failed` in `refresh.json`, on purpose, since a remote's words can quote a credential.
- `ProjectShowCommand` answers `cannot-say` for a stale reading, with no `why`.

## Plan

- `GitTreeStore.Unreachable` carries a named `cause` and git's own lines (`said`, `null` when there are none):
  `host-key` (host key verification failed), `key-refused` (`Permission denied`, an authentication failure),
  `no-network` (a name not resolved, a network unreachable, a connection refused or timed out), `timeout` (git did
  not end in time), `unreachable` otherwise. The words are `gitsOwnWords` (no `remote:` lines, nothing the forge said),
  made printable and cut to 400 characters. `fill`, `mainAt`, `founding` and the two `CommandException` catches give
  them.
- `OrgAccess.Reading` carries `@Nullable StaleWhy why` (`cause`, `said`, `at`). An unreachable read answers the
  last state with the exception's why. A kept display reads `refresh.json`: a failed refresh after the kept check
  time gives its cause, words and time; otherwise the cause is `kept` (the last state, a check runs after the answer).
  An incomplete walk gives `incomplete`.
- `org refresh-view` writes `cause`, `said` (only `Unreachable`'s, already cleaned) and keeps writing no other
  exception's message: any other failure is `failed` with no words.
- `org` and `project show` answer `stale_why` (`null` when not stale) beside `checked_at`, and in text a line
  `Stale because: <cause>: <said>`. A cached refusal carries it too.
- A stale `project show` answers repositories `cannot-say` with a `why` naming the stale cause and `project show <p>
  --refresh`.
- `doc/commands.md`, the `org` section, names the field and its causes. Changelog entry under *Changed*.
- Red first, in `OrgDisplayTest`: an unreachable `--refresh` whose fetch says `Permission denied (publickey)` answers
  `stale_why.cause` `key-refused` with git's words; a kept display after a failed `refresh-view` answers that cause;
  a kept display with no failed refresh answers `kept`; a stale `project show` gives its repositories a `why`; a
  `remote:` line is never in `said`. In git-tree, the classification of each cause from git's own lines.

## Acceptance

- The tests above, each seen red first.
- On the laptop, with the organization's forge out of reach, `porelo org --json --refresh` names why.
