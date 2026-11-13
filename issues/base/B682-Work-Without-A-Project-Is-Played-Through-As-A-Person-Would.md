# B682 — Work Without A Project Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B682",
  "title": "Work Without A Project Is Played Through As A Person Would",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "A script plays ST30 at the command line on the test VM as a person would: a task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream and a failed fetch are each named as what they are, with the next step."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST30 (work without a project, with plain refusals) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream and a failed fetch are each named as what they are, with the next step. Its issues here: [B33](README.md#b33), [B355](README.md#b355), [B457](README.md#b457), [B470](README.md#b470), [B594](README.md#b594), [B595](README.md#b595); PJ14 and the frontend's issues are not played here.

## Plan

- `acceptance/stories/st30.sh`: make a plain checkout and start a task in it, follow a project naming its repository and start again, then an empty repository, one whose upstream cannot be read and one whose fetch fails, reading each answer. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Script (2026-11-13)

`acceptance/stories/st30.sh`, run as the test account on the Ubuntu test VM against the installed porelo, with a vault
(`PORELO_VAULT`) of its own and the stub agent; its checkouts and their origins are in a temporary directory of the
run, each named `st30-<random>-<what>`:

1. A plain checkout of an origin with one commit; a task started in it with no project named, `--no-input`: it starts,
   says it went into `default` under the checkout's name, `porelo project default` lists it, `task list` shows it in
   `default`, the start says `default` has no room ([B594](README.md#b594)), and `task list` marks it unsigned
   ([B355](README.md#b355)).
2. `porelo talk peers --project default` refused with 78, naming a project repository as the way
   ([B594](README.md#b594)); `--insecure` refused as an unknown option, nothing started.
3. Inside the task, an anonymous `git ls-remote` of a public forge fails, and not as a 401 or a credential
   ([B33](README.md#b33)).
4. A followed project: the first project of the organization `porelo project list --json` names, and a repository
   address its `project show --json` names, added to `default`; the daemon's `NamedDefaultRepositories`, asked
   through `porelo daemon connect` as the window asks, offers it to that project with the command that takes it out
   ([B595](README.md#b595)), and the person takes it out. **Played only where the test account follows an
   organization's project;** otherwise the point is listed as not played, and fails the run.
5. Three refusals, each read for nothing made, for naming what it is, and for naming the next step: a checkout with no
   commit; a repository of `default` whose upstream cannot be read (a bare repository with mode 000); and one whose
   fetch fails (plain HTTP on a loopback port nothing listens on).
6. A checkout holding a `project.yml` nobody took, started without a terminal: a `porelo: warning:` line, the start
   in `default`, and no color in the captured output ([B457](README.md#b457)). A checkout whose `porelo.yml` asks for
   a host: refused without a terminal, nothing made, naming `porelo task start` at a terminal and the host
   ([B470](README.md#b470)).

Each step says what it does and what it read; the run ends with what held, what did not hold as expected, and what
did not hold, and exits 1 when anything failed unexpectedly. A trap removes the tasks, the repositories it added to
`default`, the checkouts and the vault. PJ14 and the interface's issues are not played here. No issue here names the
words of step 5's refusals; a point of step 5 that does not hold is a finding to file.

**Expected failures:** [B33](README.md#b33) and [B355](README.md#b355), in Backlog; `--expect-fail` changes the list.

**How it is run** (through the coordinator, on the laptop, from this repository's root):

    scp acceptance/stories/st30.sh <account>@<test-vm>:
    ssh <account>@<test-vm> 'bash -l st30.sh --test-account' 2>&1 | tee st30.log

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
