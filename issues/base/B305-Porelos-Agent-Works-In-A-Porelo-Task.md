# B305 — Porelo's Agent Works In A Porelo Task

```json
{
  "$schema": "issue-1.json",
  "id": "B305",
  "title": "Porelo's Agent Works In A Porelo Task",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 55,
  "blockedBy": [],
  "covers": "porelo's agent works in a Porelo task on the move's host as `Agent Core`, pushes to its Forgejo and talks in the room, with a way back to this machine; `porelo-parent` has no move of its own"
}
```

**Why this size.** M: `porelo.yml` and one run of the Build job's steps in a task here, then the trial on the rented
host, which is the operator's and the coordinator's schedule; no code and no contract change. Estimate: 63 minutes
(Core's median for M), the trial's waits not counted.

**What must be true.** porelo's agent works in a Porelo task on the move's host, as the project's procedure for an agent's
move lays out for the last builders,
and takes this issue to Review inside the task with the host's Forgejo build green. It talks in the project's room as
`Agent Core`, from the nickname `Core` that porelo-project's `project.yml` is to give the repository `core`
([B298](README.md#b298)), and can work on this machine again at any time.

## Plan

- **The repository:** `porelo`, the repository `core` in porelo-project's `project.yml`. A task holds one repository,
  so `porelo-parent` (the repository `parent`) has no move of its own: a change to it is a task on `parent` that this
  agent starts when one is needed. `porelo-adapters` and `porelo-ai.github.io` move by issues of their own
  repositories.
- **What the task runs is the Build job `build` of `.github/workflows/build.yml`, and nothing else.** Read in today's
  workflow: `proven` runs only where `github.server_url` is GitHub's, and `legs` and `native` need it, so on the host's
  forge the Build is `build` (`./mvnw -B verify -s settings.xml -Pwhole`, check-commits, the deploy dry run, the FFM
  check on `core,shield,vault`) and `deploy`, which publishes the jars to the forge's registry under
  `vars.PORELO_PUBLISH == 'false'` with `vars.PORELO_DEPLOY_PROFILE`. The native build, the package check and the
  acceptance suite stay with GitHub's legs; none of them runs in the task.
- **The task's image, `porelo.yml`:** Ubuntu by digest with the pinned GraalVM and `shfmt` already serves those steps:
  the FFM check builds no hook, so no musl toolchain is needed. Its comment changes: it is read now, as this
  repository's image, and it serves the Build job alone, not `-Pnative,dist` or the package check.
- **The unit suite in a task:** AGENTS.md promises it runs there (no podman, no `/run/user`, one uid, the kernel
  keyring refused by seccomp). The keyring's tests skip by assumption (`KernelKeyringTest`, `KeyringLeakGuardTest`);
  the run in a task names every skipped test, and a test that fails there instead of skipping is fixed in this
  issue, red first in a task.
- **Steps:** (1) `porelo.yml`'s comment; (2) a task for `core` on the development machine, online with the central
  forge as upstream (the procedure's dry run), runs the Build job's four steps green and names the skipped tests; (3)
  the trial on the rented host, as the project's procedure for an agent's move lays out, this issue taken to Review
  inside the task with the forge's Build green; (4) the way back.
- **The way back:** the task's open work is fetched from the host's forge into this machine's checkout, the
  repository's upstream switches back in `project.yml`, and the local session resumes, as the procedure lays out.

## Acceptance

- In a task on the development machine: the Build job's four steps green, with the skipped tests named.
- On the host: the procedure's check, each line green - the task runs, its push reached the forge, the forge's Build
  of it read, the pull request opened, the task in `agent-card.json`, a message naming `Agent Core` answered in the
  room.
