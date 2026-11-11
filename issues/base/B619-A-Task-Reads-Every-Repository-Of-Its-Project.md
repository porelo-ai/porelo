# B619 — A Task Reads Every Repository Of Its Project

```json
{
  "$schema": "issue-1.json",
  "id": "B619",
  "title": "A Task Reads Every Repository Of Its Project",
  "state": "Review",
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

## As built

`--all-repositories` on `porelo task start` and `allRepositories` on `Start` and `CanStart` set
`TaskLaunch.Request.allRepositories`. Beside `--repository`, `--branch`, `--from`, `--no-gate` or `--upstream` it is a
usage error (exit 64), refused by the command line before anything is asked and by the launch in the same words
(`TaskLaunch.allRepositoriesBeside`). Unnamed, the task is `all`, or the next free name. The task's agent, image and
egress are the project's own repository's, as for a start without a gate, so its `repository` row is the project's
own. A task's MCP start does not offer it.

`WorkspaceSetup.readAll` opens each repository's gate as a workspace does (`GateSupport.gate`, `initialize`,
`refresh`; an offline project's mirror is not fetched) and `GitGate.readMain` writes `refs/heads/main`, else the
branch the mirror's HEAD names, with `git archive` into a file and `tar --extract --exclude=.git`, git run without the
system's or the account's configuration. The incoming refs are never read. A repository that cannot be read is named
in the start's output and left out; none readable refuses the start with exit 69, after it joined its conversations,
so it is ended as every refused start is.

Differs from the plan:

- **The trees are in the account's state directory, `repositories/<container>/`** (`TaskPaths.repositories`), not
  the container's state directory: that is the runtime directory, which is memory and is cleared by a reboot, while
  the container mounts this one. `TaskRunner` mounts it read only at `/workspace` when it exists, and every removal
  takes it: `task remove`, a prune, and a start that failed before its container existed.
- **`CanStart` answers from the options alone**, with a new `StartOutcome` `ALL_REPOSITORIES_REFUSED` for
  `repository`, `branch` or `noGate` beside it. Whether a repository can be read is not asked there, since that reaches
  every upstream; there is no `NoRepositoryReadable`.
- **What is not read is named in the start's output**, not in a log of its own; the agent's guide lists the
  directories it has and says that a repository missing there could not be read.
- **The census of unhanded work answers nothing held for a workspace that cannot be written**, which is how a
  running reading task answers, and the start notes that it holds nothing, so a stopped or killed one is removed
  without `--force`. Its end removes the container also when the run failed. `task stop` still only stops it.
- A reading task needs no task certificate on a host: it has no gate and signs nothing.
- The end's test is in `TaskRunCommandTest`, which drives the launch.
