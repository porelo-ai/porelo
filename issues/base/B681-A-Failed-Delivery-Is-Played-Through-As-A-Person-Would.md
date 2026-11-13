# B681 — A Failed Delivery Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B681",
  "title": "A Failed Delivery Is Played Through As A Person Would",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "A script plays ST39 at the command line on the test VM as a person would: the forge stops answering and other delivery refusals are triggered; each answer ends within the configured bound with its own reason, the failure stays visible in `task list`/`task status`, the card names its repository, and a project push the person makes is picked up."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST39 (a failed delivery named and kept visible) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: the forge stops answering and other delivery refusals are triggered; each answer ends within the configured bound with its own reason, the failure stays visible in `task list`/`task status`, the card names its repository, and a project push the person makes is picked up. Its issues here: [B179](README.md#b179), [B197](README.md#b197); the frontend's F117 is not played here.

## Plan

- `acceptance/stories/st39.sh`: stop the test forge's service on the VM (and bring it back), deliver from a task, read the answers' exit codes and words against the bound, read the task's status afterwards; push to the project's repository as the person and see it taken. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Script (2026-11-13)

`acceptance/stories/st39.sh`, run as the test account on the Ubuntu test VM against the installed porelo, with a vault
(`PORELO_VAULT`) and an online project `st39-<random>` of its own and the stub agent. The VM's own forge is not touched:
stopping it needs root and would stop it for every account, so the script stands up a forge of its own on loopback.

1. A bare repository served over plain HTTP on `127.0.0.1` by `git http-backend` behind a small Python server, and an
   online project whose `central` it is, filled from the project's checkout.
2. A task started in the project; a push from it while the forge answers, seen at the forge at once.
3. Three refusals, each timed against the bound: the forge refusing (a `pre-receive` hook, whose words must reach the
   agent), the gate refusing a push to `main`, and the gate refusing a tag.
4. The forge stops answering: its server stopped and a listener in its place on the same port that takes the
   connection and never answers, as [B197](README.md#b197)'s test does. A push from the task must end within the
   bound - `limits.pass_on`, two minutes unless `--pass-on` sets it - saying the forge did not answer and that
   nothing was passed on, not that it refused. The reasons of steps 3 and 4 must each be their own.
5. The failure afterwards: the task's `gate.log` keeps what the forge did, `task status` shows the work not delivered
   as never pushed to the gate, `task list` still lists the task, and `task status --json` names its repository.
6. The forge back: the person pushes a commit to the project's repository, the task's next `git fetch porelo main`
   picks it up, and a push from the task goes through again.
7. A message to nobody, held; `porelo talk release` of it, `--refuse`, then `release` again, refused as refused for
   good; the same release asked of the daemon (`Release` through `porelo daemon connect`) answers `NOT_DELIVERABLE`
   with the code `REFUSED_BEFORE` ([B179](README.md#b179)).

Each step says what it does and what it read; the run ends with what held, what did not hold as expected, and what
did not hold, and exits 1 when anything failed unexpectedly. A trap removes the task, the project, its image, the
forge and the vault, also when the run stops part-way. The card of the interface (F117) is not played here.

**Expected failures:** [B197](README.md#b197) for the bound and the silence's words, [B179](README.md#b179) for the
code; `--expect-fail` changes the list (`none` when both are in Review). Until [B197](README.md#b197) is built the
task's push to the silent forge is cut at the bound plus a minute, and the gate's own push ends when the silent
listener is stopped.

**How it is run** (through the coordinator, on the laptop, from this repository's root):

    scp acceptance/stories/st39.sh <account>@<test-vm>:
    ssh <account>@<test-vm> 'bash -l st39.sh --test-account' 2>&1 | tee st39.log

- `--pass-on 30s` writes `limits.pass_on` into the project file and takes it as the bound; only once
  [B197](README.md#b197) is built does the project file take the key, so until then the run uses the default.

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

- Played on 2026-11-13 on walk4, an Ubuntu 26.04 guest, in the account `core` with no sudo, against Porelo `0.5.1~snapshot.20261113T172922` installed into that account (`$XDG_BIN_HOME`, its own `porelod.service`) and the stub agent. The run was `bash -l st39.sh --test-account`. The last run is `st39.log`; the first, before
  the script fixes, is `st39.first.log`.
- **Held (25):**
  - The loopback forge answers, and a push from the task arrives there at once.
  - A push the forge refuses fails within the bound and reaches the agent in the forge's own words.
  - The gate refuses a push to `main` and a tag within the bound.
  - A push to the silent forge fails rather than passing, says the forge did not answer, and is not called a
    refusal. The forge's, the gate's and the silence's reasons are each their own.
  - The task's `gate.log` keeps what the forge did. `task status` shows the work never pushed to the gate, `task
    list` still lists the task, and `task status --json` names its repository.
  - The task's next `git fetch porelo main` picks up the person's push, and a push from the task goes through again.
  - A message to nobody is held; a release after `--refuse` is refused, saying so. The daemon's `Release` answers
    `NOT_DELIVERABLE` with the code `REFUSED_BEFORE` ([B179](README.md#b179)).
- **Did not hold, both [B197](README.md#b197)'s, recorded in its "Found in play-through":**
  - The push to the silent forge ended after 151 s, against the bound of 120 s: the gate asks after the push for up
    to 30 s more.
  - Its refusal says "whether it took the push is not known", not "nothing was passed on".
- **Script fixes:**
  - The container is named `porelo-<project>_<task>`.
  - The gate's refusals of a branch and of a tag share one reason, so the tag's is kept apart.
  - "Not called a refusal" reads the words alone.
  - The default `--expect-fail` is `none`.
- The script fixes are on the stack, in the commit "Fit four story scripts to the product as played on a test VM".
