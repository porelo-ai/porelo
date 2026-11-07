# [B491](README.md#b491) — A Repository Named By Key Or Full Name

**Status:** Implementing; built on the operator's word that every finding of the trial move is fixed before the
second move.
**Size:** S - one lookup shared by every command with `-r`, and its tests.

**What must be true.** Wherever a command takes `-r`/`--repository`, a person may name a repository by the key
`project.yml` gives it (`sluice`) or by its full name, the last part of its address without `.git`
(`porelo-message-sluice`), when exactly one repository of the project has that name. A name that matches none or more
than one is refused, and the refusal lists every repository as `<key> (<full name>)`.

## Found

- **Seen during the trial move.** `task start -r porelo-message-sluice` was refused, since only the key `sluice` is
  looked up, and the refusal listed keys only, while the window and the guide show full names.
- `GateSupport.repository(project, named)` looks the name up with `project.repository(named)`, keyed by the project
  file's key, and its refusal lists `OwnRepository.worksIn(project)`, which gives keys only.

## Plan

- The lookup in `GateSupport.repository(project, named)` takes the key first; with no key of that name, the one
  repository whose full name equals it. The full name comes from the repository's central address, or its upstream
  where it has no central one.
- Two repositories with the same full name and neither keyed by it: refused as ambiguous, naming both keys.
- The refusal names each repository a task may work in as `<key> (<full name>)`, or the key alone where there is no
  address.
- Every caller of `GateSupport.repository` and the task start's own lookup take it from that one place, so `task
  start`, `gate` and `egress` agree.
- **Red first:** tests of the lookup for a full name, a key, an ambiguous full name and an unknown name, the last two
  asserting the refusal's words list both forms.

## Acceptance

- The tests above fail on today's lookup and pass after.
- `task start -r <full name>` reaches the same repository as `-r <key>` in a unit test of the start's resolution.
