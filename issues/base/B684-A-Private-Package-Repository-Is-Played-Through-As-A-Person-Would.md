# B684 — A Private Package Repository Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B684",
  "title": "A Private Package Repository Is Played Through As A Person Would",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 33,
  "blockedBy": [],
  "covers": "A script plays ST43 at the command line on the test VM as a person would: a task builds against an authenticated Nexus or Artifactory stand-in; the build tool's settings are written at the start and the credential stays in the vault, never inside the task."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed and a stand-in repository with TLS and Basic auth.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST43 (a company's private package repository, with no credential in the task) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a task builds against an authenticated Nexus or Artifactory stand-in; the build tool's settings are written at the start and the credential stays in the vault, never inside the task. Its issues here: [B219](README.md#b219), [B222](README.md#b222), [B467](README.md#b467), [B468](README.md#b468); the ecosystems' EC16 is used through its adapter.

## Plan

- `acceptance/stories/st43.sh`: serve a Maven repository that demands Basic auth over TLS on the VM (a small stand-in serving one jar, its authority added), put its credential with `porelo vault put`, declare it in the project's `tool_repositories` with `credential`, start a task that builds a project depending on the jar; then search the task's files, environment and processes for the credential and find none. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

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
