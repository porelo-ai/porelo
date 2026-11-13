# B683 — A Start That Waits For Its Helpers Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B683",
  "title": "A Start That Waits For Its Helpers Is Played Through As A Person Would",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 32,
  "blockedBy": [],
  "covers": "A script plays ST53 at the command line on the test VM as a person would: a start waits until its gate and relay are both up; a start whose gate or relay does not come up ends with its code; two starts of one name never stop each other's task."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST53 (a start that waits for its helpers) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a start waits until its gate and relay are both up; a start whose gate or relay does not come up ends with its code; two starts of one name never stop each other's task. Its issues here: [B384](README.md#b384).

## Plan

- `acceptance/stories/st53.sh`: start a task and read its steps; make the gate and then the relay fail to come up (a blocked port or a stopped helper) and read the code; start two tasks of one name at once and see neither stops the other. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Script (2026-11-13)

`acceptance/stories/st53.sh`, run as the test account on the Ubuntu test VM against the installed porelo, with a vault
(`PORELO_VAULT`) and a guarded project `st53-<random>` of its own and the stub agent:

1. A start of a task with a gate and a credential relay; read the moment it returns: its code, and whether `gate.pid`
   and `relay.pid` in the task's state directory name running processes.
2. A start whose gate cannot record that it is up: a directory stands where the gate writes its pid, so it never
   records one. Read the start's code (70 expected) and whether any process still names the task's state directory.
3. The same for the relay (`relay.pid`).
4. Two starts of `twin` at the same moment: read both codes, then whether the container still runs with its gate.

Each step says what it does and what it read; the run ends with what held, what did not hold as expected, and what
did not hold, and exits 1 when anything failed unexpectedly. A trap removes the tasks, the project, its image, the
state directories it made and the vault, also when the run stops part-way.

**Expected failures:** [B384](README.md#b384) for every point of steps 1 to 4 except that a start answers 0 and that
one of the two starts starts the task; `--expect-fail` changes the list (`none` when [B384](README.md#b384) is in Review).

**How it is run** (through the coordinator, on the laptop, from this repository's root):

    scp acceptance/stories/st53.sh <account>@<test-vm>:
    ssh <account>@<test-vm> 'bash -l st53.sh --test-account' 2>&1 | tee st53.log

## Rules for a story test (operator, 2026-11-12)

- A test script is test tooling, not production code: no tests for the script, no red-first, no review loops (one
  Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed on the spot and the run carries on; every fault found, in the script or in the
  product, is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script
  fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: asked before it starts, posted when it ends. No second
  VM, no Molecule beside it; cleaned up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue
  as an expected failure.

## Acceptance

- The script's run on the VM shows every point of the story's test holding, or names each that does not with the
  issue it waits on.

## Built

- Played on 2026-11-13 on walk4, an Ubuntu 26.04 guest, in the account `core` with no sudo, against Porelo `0.5.1~snapshot.20261113T172922` installed into that account (`$XDG_BIN_HOME`, its own `porelod.service`) and the stub agent. The run was `bash -l st53.sh --test-account`. The last run is `st53.log`; the runs before the
  script fixes are `st53.first.log`, `st53.second.log` and `st53.third.log`.
- **Held, all 10 points:**
  - A start answers 0 with its gate and its credential relay both running.
  - A start whose gate, or whose relay, cannot record its pid ends with 70 ("the git gate did not come up", "the
    endpoint relay did not come up") and leaves none of its helpers running.
  - Of two starts of `twin` at the same moment, one answers 0 and the other 64, naming the task that holds the name.
    The task keeps running with its gate.
- **Did not hold:** nothing.
- **Script fixes:**
  - The container is named `porelo-<project>_<task>`.
  - The pid file's directory is put in place once the start has made the state directory, since a state directory
    there before the start is refused as "occupied or unrecognized".
  - Every start passes a second credential (`--credential st53=openrouter`), since the stub agent needs no relay of
    its own.
  - A helper that failed clears its pid path, and its name in the start's words counts as the obstruction holding.
  - The default `--expect-fail` is `none`.
- The script fixes are on the stack, in the commit "Fit four story scripts to the product as played on a test VM".
