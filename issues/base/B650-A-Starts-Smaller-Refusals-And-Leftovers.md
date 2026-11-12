# B650 — A Start's Smaller Refusals And Leftovers

```json
{
  "$schema": "issue-1.json",
  "id": "B650",
  "title": "A Start's Smaller Refusals And Leftovers",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 43,
  "blockedBy": [],
  "covers": "A start leaves no unrecorded container, a no-gate task is removable, a long project name and a refused task name get a usable answer, two refusals answer one code, and a host start names the builds entry it misses",
  "openQuestions": 0
}
```

Split out of the gathering issue for small edge cases on 2026-11-12 (ST56), by the operator's decision. Each point was found by a reading or a fix, and reaches no other person's key, card, socket, mailbox or container.

**What must be true.** A start that fails leaves nothing Porelo cannot reach, and each refusal names what the person can do:

- Low: if `podman create` succeeds but recording its id fails, the container Porelo made stays without a record;
  `task remove` cannot reach it and the name stays taken. Found while fixing
  a failed start's cleanup.
- Low: with `--no-gate`, `/workspace` is never a git checkout, so a stopped no-gate task's census reads as unknown
  and `task remove` needs `--force` (read in `UnhandedWork.CENSUS`, not measured). Found while fixing
  a failed start's cleanup.
- Low: a project's name written by hand at 55 characters or more leaves a task so little room that even the default
  `work-2` does not fit; the start then refuses with the sentence that names the room, or that the project's name is
  too long, rather than choosing a name.
- Low: the refusal of a task name with characters no task may have suggests a lowercased one checked only against the
  pattern, so it can suggest a name another rule refuses: digits only (`123 ` suggests `123`), `people`, one beginning
  `porelo-`, or one too long for the project.
- Low: a start wrong twice, an unknown `-r` and another refusal, hears whichever check comes first, and the unknown
  name exits 64 where the other would exit 2; both refuse before anything is made. Found by [B491](README.md#b491)'s reading.
- Low: on a host whose builds come from its file, a start with the vault locked and an agent that takes no credential
  cannot see whether the vault holds the builds entry, so the helper starts and, once the vault opens without it,
  says to store it with `porelo vault put` instead of naming `porelo-host-forge-token`. Found by [B493](README.md#b493)'s reading.

## Plan

- `TaskLaunch`: the container id is recorded before anything else can fail, or the container is removed by the id `podman create` printed.
- `UnhandedWork.CENSUS`: a `--no-gate` task counts as having no checkout, not as unknown.
- `TaskNames`: the suggestion is checked against every rule; a project name that leaves no room is refused at `project init` with the room named.
- The order of a start's checks gives one exit code for the same mistake, whichever comes first.
- First red: `TaskRunCommandTest`, a record that fails after `podman create` leaves no container.

## Acceptance

- Each point above has its case seen red first.
