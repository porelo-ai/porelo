# B685 — The Gathered Findings Are Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B685",
  "title": "The Gathered Findings Are Played Through As A Person Would",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 34,
  "blockedBy": [],
  "covers": "A script plays ST56 at the command line on the test VM as a person would: the gathered findings' points, porelo's, HO86's and F288's, each hold nothing open; a point that proved larger is an issue of its own."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST56 (the gathered findings worked off) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: [B380](README.md#b380)'s, HO86's and F288's points each hold nothing open; a point that proved larger is an issue of its own. Its issues here: [B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651), [B203](README.md#b203), [B666](README.md#b666); HO86 and F288 are other repositories'.

## Plan

- `acceptance/stories/st56.sh`: for each point of the gathered findings that a person can see at the command line, do what it names and read the answer; a point that is an issue of its own is checked to be filed. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Script (2026-11-13)

`acceptance/stories/st56.sh`, run as the test account on the Ubuntu test VM against the installed porelo:

1. The issue indexes of porelo, porelo-host and porelo-frontend, read from GitHub's main branches or from a directory
   given with `--issues`: [B380](README.md#b380) and porelo-host's HO86 are closed and name where their points went
   ([B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651), the points not worth it; [B666](README.md#b666)); F288 is filed; [B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651), [B203](README.md#b203) and [B666](README.md#b666) are filed, and
   each is checked to be in Review or Finished.
2. [B649](README.md#b649): a commit signed with an SSH key, new to a bare repository as a push brings it, judged by
   `porelo commit verify` with an allowed-signers file of the signer's line alone, then beside a line holding a lone
   CR, a line ssh-keygen cannot parse, and a line beginning with U+2000 before `#`, with `git verify-commit`'s verdict
   on the same file read beside it.
3. [B650](README.md#b650): a stopped `--no-gate` task removed without `--force`; the suggestions for the names `123 `,
   `People` and `Porelo-x` (not `123`, `people`, `porelo-x`); an unknown `-r` alone and beside a refused name
   answering one code; `porelo project init --project` with a 62-character name refused, naming the room (`--name` names the
   repository, not the project).
4. [B203](README.md#b203): `porelo task files` and `porelo task status` answering by the task's name and by its
   container's name.

[B651](README.md#b651) (tests only), [B666](README.md#b666) (a host's push judge) and F288 (the desktop interface)
show nothing at this command line and are checked as filed. A trap removes the tasks, the project, its image, the
checkout and the vault.

**Expected failures:** none, since [B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651),
[B203](README.md#b203), [B666](README.md#b666) and F288 are in Review; `--expect-fail` changes the list.

**How it is run** (through the coordinator, on the laptop, from this repository's root; the `--issues` directory
only while the indexes on GitHub are older than the local ones):

    scp acceptance/stories/st56.sh <account>@<test-vm>:
    ssh <account>@<test-vm> 'bash -l st56.sh --test-account' 2>&1 | tee st56.log

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

- Played on 2026-11-13 on walk4, an Ubuntu 26.04 guest, in the account `core` with no sudo, against Porelo `0.5.1~snapshot.20261113T172922` installed into that account (`$XDG_BIN_HOME`, its own `porelod.service`) and the stub agent. The run was `bash -l st56.sh --test-account --issues ~/issues`, with porelo's index from the stack and
  porelo-host's and porelo-frontend's from GitHub's main, since GitHub's porelo index was older. The last run is
  `st56.log`; the first, before the script fix, is `st56.first.log`.
- **Held, all 32 points:**
  - [B380](README.md#b380) and HO86 are closed and name where their points went. F288 is in Review.
    [B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651), [B203](README.md#b203) and
    [B666](README.md#b666) are filed and in Review.
  - [B649](README.md#b649): `porelo commit verify` accepts the signed commit with a plain signers file, and beside a
    lone CR, a line ssh-keygen cannot parse and a U+2000 before `#`, as `git verify-commit` does.
  - [B650](README.md#b650):
    - a stopped `--no-gate` task is removed without `--force`;
    - `123 `, `People` and `Porelo-x` are refused with no suggestion that another rule refuses;
    - an unknown `-r`, alone and beside a refused name, answers 64 both times;
    - `project init --project` with 62 characters is refused with 64, naming the room.
  - [B203](README.md#b203): `task files` and `task status` answer by the task's name and by its container's name.
- **Did not hold:** nothing.
- **Script fixes:**
  - The container is named `porelo-<project>_<task>`.
  - `project init` takes `--project`.
  - The default `--expect-fail` is `none`.
- The script fixes are on the stack, in the commit "Fit four story scripts to the product as played on a test VM".
