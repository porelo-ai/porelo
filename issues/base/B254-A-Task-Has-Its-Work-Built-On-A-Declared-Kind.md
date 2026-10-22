# B254 — A Task Has Its Work Built on a Declared Kind

**Status:** Idea; blocked by [B253](README.md#b253) and porelo-buildtools' machine kinds (part 1 of porelo-project
[PJ22](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj22), not yet filed there).
**Size, proposed:** L.

**Decided:** it stays an Idea, as it is not needed for porelo-project
[PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39): a task's push already starts the
whole workflow on the local forge, its acceptance legs on the VM pool included, and the task follows that build as it
follows any.

**What must be true.** A task asks for its work to be built on a declared machine kind and reads the verdict and the
log in `/porelo/files`. A request for a kind or a command the project does not declare is refused.

## Why

From porelo-project [PJ22](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj22), part 5: "*A
task's build* on a declared kind, read through `Task.builds` (`porelo`)."

Read in today's code: a task's builds are *followed*, not asked for. A helper on the host follows every commit an
online task pushes, through the reader the project names under `builds`, and hands each verdict and failed log into
`/porelo/files`; `Task.builds` reports them (`15-task.varlink`, `TaskBuilds`, `BuildsWiring`). The task asks for
nothing and holds no forge credential. What is missing is the request: a task that wants its work built on a kind of
machine it does not run on (amd64 with KVM, a Windows runner, arm64) has no way to say so but pushing to a branch
whose workflow happens to do it.

## The shape proposed

- **The project declares what may be asked for**, under `builds`: each kind a task may name, and each command it may
  run there, by name. Nothing else can be asked for; a task never names a machine, a provider or a raw command.
- **The task asks through its socket**, as it hands in files today: kind and command by name, for the commit it has
  at hand. Porelo checks both against the declaration before anything leaves the host, then has the work built on
  the forge the project names, on a machine of that kind, through porelo-buildtools' kinds.
- **The answer arrives as today's builds do:** `build-<commit>.txt` and the logs in `/porelo/files`, and the build
  in `Task.builds`. The task still holds no forge credential.

## To be checked

- How the request reaches the forge without the task's work being pushed somewhere a person did not approve: a
  branch on the local forge only, named for the task, or the work handed over without a push.
- Whether an offline or guarded project may ask at all: today `builds` is for an online project only, and a build
  on another machine carries the task's work off this one.
- What porelo-buildtools' kinds give back (a machine, a runner label), and so whether the request names a workflow
  input or starts a run itself.
