# B197 — A Push To A Slow Forge Has A Bound

**Status:** Estimated.
**Size:** M - a key of the project file (`core`'s `Limits`, `ProjectReader`, `ProjectSchema`), a per-command limit
in `core`'s process runner, the gate in `gate` and `app-model`, their tests and `doc/`; no contract another repository
reads.

**What must be true.** When the gate passes an online task's push on to the upstream (`GitGate.passOn`) and the forge
is slow or does not answer, the push ends within a stated time. The agent gets a named refusal that says the forge did
not answer, and it never waits without end. The time is set in the project file, as the project's other limits are:
`limits.pass_on`, for the project and per repository.

## Why

Left open when the push through the host was built: `passOn` sets no time limit of its own.

## What is there today

Read in the code on 2026-10-17:

- **The bound is the runner's general one.** The task's gate is made in `GateSupport` (`app-model`, `:165`) with
  `new ProcessCommandRunner()`, whose default limit is ten minutes (`ProcessCommandRunner:24`). `passOn`
  (`GitGate:741`) runs its `git push` through it, via `gitWith`.
- **The agent waits the same ten minutes.** `GitHttpServer` (`:344`) calls `passOn` while the agent's push request is
  open, so the agent's `git push` gets no answer until the gate's ends.
- **The gate holds a slot for that time.** The gate serves four requests at once, and a hung pass-on holds one.
- **What the agent reads then says the wrong thing:** "the upstream refused it: ... Timed out after 600 s"
  (`GitHttpServer.passOn`, `:484`). The forge refused nothing; it did not answer.
- **The work is not kept:** on any refusal the incoming ref is set back (`:494`), so the agent must push again. That
  stays as it is.

## Plan

1. **`core`: the key.** `limits.pass_on` in `project.yml`, written as `hand_in` is: a string with its unit, here a
   time - `"90s"`, `"2m"`, `"1h"` - two minutes unless set. It lies between ten seconds and one hour; anything else is
   refused by name when the file is read, and there is no `none`, since the point is that the push ends.
   - `Limits` gains `passOn` (a `Duration`, `DEFAULT_PASS_ON`) and a reader `Limits.duration(text, key)` beside
     `Limits.bytes`; `Limits.Declared` gains it for a repository's own `limits`, which replaces the project's key by
     key (`ProjectReader.declaredLimits`, `:551`, and the project's at `:622`).
   - `ProjectSchema` lists it with its default, beside `hand_in` (`:101`), which says that `m` there is minutes.
   - Like `hand_in`, it is fixed when the task starts: the gate is made for the task then.
2. **`core`: a command may carry its own limit.** `Command.withTimeout(Duration)`, and `ProcessCommandRunner` takes
   the shorter of its own limit and the command's. A timeout is told apart from a failure: `CommandException` says
   that the limit was reached (`timedOut()`), so a caller need not read the text.
3. **`gate`: the pass-on's limit.** `GitGate` takes the limit when it is made; `GateSupport` (`app-model`, `:165`)
   hands it the repository's `limits.pass_on` from `Project`'s limits for that repository. `passOn` runs its push
   with it. A
   timeout becomes `GateException.ForgeSilent`, a named refusal like `BranchExists`: "the forge did not answer within
   120 s; nothing was passed on - push again". A forge that refuses keeps its own message.
4. **`GitHttpServer.passOn` passes the message on as it comes**, so the "the upstream refused it:" prefix moves into
   `GitGate`'s refusal and a silence is not called a refusal.
5. **`doc/project-file.md`**: the key in the example under `limits` and in its list; **`doc/security.md`**, where the
   online class's push through the host is described, and **`doc/running.md`**: the bound, and that the agent pushes
   again.
6. A changelog entry, `fixed`.

## How it is tested

- **Red first:** in `GitGateTest`, an online gate whose upstream is an HTTP address on loopback that accepts the
  connection and never answers. `passOn` returns `ForgeSilent`'s message within the limit the gate is given, two
  seconds here. Today it waits the runner's ten minutes, so the test, bounded at 30 seconds, fails.
- **The key, red first** in `ProjectReaderTest`: `limits.pass_on: "90s"` reads as 90 seconds, a repository's own
  value replaces the project's, an absent one is two minutes, and `"0s"`, `"2h"`, `"none"` and `"90"` are refused
  naming the key. `DocumentedProjectFileTest` holds that `doc/project-file.md` lists it.
- In `GateLimitsTest`, through `GitHttpServer`: the agent's push gets that message, and the incoming ref is back where
  it was.
- In `ProcessCommandRunnerTest`: a command's own shorter limit ends `sleep 30` within it and says so with
  `timedOut()`; the runner's own limit still holds for a command without one.
- A forge that refuses quickly still reaches the agent with its own words (the existing test stays green).

## Acceptance

- An online task's push to a forge that does not answer ends within `limits.pass_on` - two minutes unless the
  project file says otherwise - with a refusal saying the forge did not answer and that nothing was passed on.
- The project file sets it for the project and per repository, and refuses a value outside ten seconds to one hour.
- A refusal by the forge still says what the forge said.

## Open questions

None. The default, two minutes, is long enough for a large push to a forge that answers slowly, and short of the
ten minutes that hold a slot of the gate; a project whose pushes are larger sets its own.
