# B802 — A Task Started On A Host Is Given Its Files

```json
{ "$schema": "issue-1.json", "id": "B802", "title": "A Task Started On A Host Is Given Its Files", "state": "Analyzed",
  "size": "M", "priority": "High", "rank": 1, "blockedBy": [],
  "covers": "`porelo task run --host` takes `--given`: the files go to the host on the start's input, beside the certificate, never as arguments; the host keeps and mounts them as a local start does" }
```

**Why this size.** One contract change on the start's input between the two ends of a host start (`RemoteStart`, the
host's `--certify-stdin` side), its tests, `doc/running.md` and one acceptance step on a host.

**What must be true.** A person's planner that keeps its issues outside the repositories starts a task on a host and
gives it the issue as it holds it now, exactly as it does for a start on this machine: the task reads it at
`/run/porelo/given/<name>`. Today a start with `--host` refuses `--given`, so a task on a host can only read its issue
from the host's copy of the repository, and once no repository holds an issue file it has nothing to read.

## Plan

- **The contract.** `porelo task run --host <login>@<host> --given <name>=<file>` reads each file on this machine
  with the same checks as a local start (`TaskGiven.refusal`: at most 16 files, 1 MiB each, UTF-8, one file name
  each, 4 MiB together), then sends them to the host on the start's standard input as one JSON line
  `{"given": {"<name>": "<text>"}}` before anything else, never on the ssh command line. The host's start reads that
  line first, runs the same `TaskGiven.refusal` again, and refuses with exit 64 and the same `--given: <why>` text
  before it makes anything. A start without `--given` sends `{"given": {}}`, so the host never waits on a line that
  does not come.
- **This machine** (`apps/app-task`): `TaskRunCommand.hostRefusal` drops the `--given` refusal; `readGiven` runs
  before the host start; `RemoteStart` writes the given line to the process's input before it reads the host's
  certificate request, on the writer it already holds.
- **The host** (`apps/app-task`): `TaskRunCommand` with `--certify-stdin` reads the given line before the start, and
  `StdinCertifier` reads the certificate from the same reader afterwards, so one buffered reader serves both.
  `TaskLaunch` keeps and mounts the files as for a local start (`TaskGiven`), and a task brought back on the host
  still refuses `--given`.
- **The reason a host start given work follows central first** (`CentralFreshness`, `TaskLaunch.centralRefusal`) is
  written as what stays true: a task given work starts on central's newest main. Its words stop naming an issue
  the planner saved there.
- **Docs**: `doc/running.md` (`--given` and `--host`), the option's help text in `TaskRunCommand`.
- **The test that goes red first**: `TaskRunCommandTest` - a host start with `--given` is no longer refused, and the
  fake host receives the given line on its input before the certificate request is answered; `StdinCertifierTest` -
  the certificate is still read after a given line; a host-side test - a given line that `TaskGiven.refusal`
  refuses ends the start with 64 before anything is made.
- **Acceptance**: one step in the host feature on the VM - a task started with `--host` and `--given issue.md=...`
  reads the text at `/run/porelo/given/issue.md`, and the host's process list never shows it.

## Acceptance

- A host start given a file shows its text in the task at `/run/porelo/given/<name>`; a start given a file the rules
  refuse is refused on this machine before ssh is opened, and a host that receives such a line refuses it with 64.
- The file's text appears in no command line on either machine (`ps` on the host during the start) and in no log.
- A host start without `--given` behaves as today, certificate included.

## Open questions

- Should a task on a host that is given its work still be refused when the host's copy is behind central, once no
  issue lives in a repository? Proposed: yes - it still keeps a task off old code - with the reason reworded as in
  the Plan.

## To be checked

- Whether the person's planner starts a task on a host through `porelo task run --host`, or through another call
  that would need `given` too; asked of the planner's agent.
- That an older host that does not read the given line fails the start loud rather than reading the JSON as the
  certificate; if it does not, the line carries a version the old reader refuses.
