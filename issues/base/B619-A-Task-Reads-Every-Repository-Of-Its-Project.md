# B619 — A Task Reads Every Repository Of Its Project

```json
{
  "$schema": "issue-1.json",
  "id": "B619",
  "title": "A Task Reads Every Repository Of Its Project",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task started to read its whole project sees every repository's main as a directory under /workspace, read only, taken wherever this machine takes each repository; it pushes nothing and leaves nothing behind."
}
```

**Why this size.** one start option across the wire, the daemon's request and the CLI; a workspace assembled on the
host from one gate per repository and mounted read only; this kind of task exempted from keeping unhanded work; unit
tests over a stand-in podman and gate.

Filed 2026-11-11 as porelo's part of `porelo-project` ST319, "Discuss an idea with an agent in the planner", in the
shape the operator gave PJ58: no forge repository of them all, the task's start assembles it.

**What must be true.** A person starts a task to discuss an idea for a project. The agent reads every repository
of the project, as one tree: `/workspace/<repository>/` for each, the project's own repository among them, each at
its main as this machine takes it (from the host's own forge, from central, or a person's own mirror). Nothing in
`/workspace` can be written, no gate takes a push, and when the task ends its container is removed without asking
about unhanded work, so nothing staged survives.

How it is today, read in the code:

- A task has exactly one repository: `WorkspaceSetup.openWorkspace` opens one gate, and the container clones
  `/workspace` from it (`TaskWorkspace.cloneScript`). `--no-gate` means no workspace at all.
- `GateSupport.gate` with `initialize` and `refresh` brings any repository's mirror up to its upstream with the
  credentials this machine has for it (`HostForgeLending`, `VaultGitCredentials`); `GitGate.checkout` already makes
  a hook-free copy of a ref on the host.
- `ContainerSpec.volumeReadOnly` mounts a host directory `:ro`.
- Changed files in `/workspace` keep a task's container at its end (`UnhandedWork`, `TaskLaunch.cleanUpOrFail`).

## Plan

- A start option `allRepositories` (wire `Start` and `CanStart`, `PoreloDaemon.request`, `TaskLaunch.Request`,
  `porelo task start --all-repositories`), refused beside `--repository`, `--branch` and `--from`.
- `WorkspaceSetup` opens, for each repository of the project, its gate as today and refreshes it, then writes the
  main's tree (`git archive` of `refs/heads/main`, never the incoming refs) into the task's state directory, and
  mounts that directory read only at `/workspace`. A repository that cannot be read is named in the task's log and
  left out; none readable refuses the start.
- No gate token and no push target in the container; the task's end removes it whatever `/workspace` holds.
- Red first: a `WorkspaceSetupTest` over two stand-in gates sees both trees under `/workspace`, mounted `:ro`, and
  no incoming ref in either; a `TaskLaunchTest` sees the container removed at the end with a change in it.
- `doc/commands.md` names the option. Changelog entry under *Added*.

## Acceptance

- The tests above, seen red first.
- On a host and on a person's machine, a task started with `--all-repositories` lists every repository of its
  project under `/workspace`, fails to write there, and is gone after its end.
