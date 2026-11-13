# B197 — A Push To A Slow Forge Has A Bound

```json
{
  "$schema": "issue-1.json",
  "id": "B197",
  "title": "A Push To A Slow Forge Has A Bound",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 52,
  "blockedBy": [],
  "covers": "A push the gate passes on to a forge that is slow or down ends within `limits.pass_on` of the project file, two minutes unless set, with a named refusal that the forge did not answer."
}
```

**Why this size.** a key of the project file (`core`'s `Limits`, `ProjectReader`, `ProjectSchema`), a per-command limit
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

## Done

Built as planned, with two differences from the code the plan read on 2026-10-17. A repository's own `porelo.yml`
already layers any `limits` key over the project's, so `limits.pass_on` needed no `Limits.Declared` of its own. And
the online pass-on now runs in `gate serve`, so the task's start settles the limit, with the repository's file
merged, and hands it over as `--pass-on-within <seconds>`, as it does `--branch`.

- **The key.** `limits.pass_on` takes a time with its unit (`90s`, `2m`, `1h`), two minutes unless set. Outside 10s
  to 1h, or written otherwise (`none`, `90`, `1.5m`), it is refused by name when the file is read. `ProjectSchema`
  lists it, and `doc/project-file.md`, `doc/security.md` and `doc/running.md` say what it bounds.
- **A command's own limit.** `Command.withTimeout` sets it, and `ProcessCommandRunner` takes the shorter of its own
  and the command's. `CommandException.timedOut()` tells a limit from a failure, and `UnattendedGit` carries the limit
  through its preparation.
- **The gate.** `GitGate.passOn` pushes within its limit, and a forge's refusal keeps its own words after "the
  upstream refused it:". A push ended at its limit may have landed before the forge stalled, so the gate asks with a
  bounded `ls-remote` (the limit, at most 30 s): if the branch holds the commit the push succeeds; otherwise
  `GateException.ForgeSilent` says the forge did not answer within N s and either did not hold the push when asked
  after, or that whether it took it is not known. N is the limit that ended it, which `CommandException.limit()`
  carries - the runner's when that was shorter. `GitHttpServer` passes the message on as it comes, so a silence is
  not called a refusal.
- **Setting the ref back.** `GitHttpServer` sets the incoming ref back with a conditional `update-ref`, only while it
  still holds what the refused push wrote, so a push taken meanwhile stays.
- **The host's own forge.** `HostPush` waits for another push of the same task within the same limit
  (`FileLocks.holdingWithin`) and pushes with what is left of it. It sets the mirror's branch back, conditionally,
  when the forge did not take the push, and asks after a push ended at its limit as the gate does, writing its record
  when the forge holds the commit.
- **Red first.**
  - `ProcessCommandRunnerTest`'s two limit tests failed against stubs, and 7 of `PassOnLimitTest`'s 16 cases did. Its
    refusals passed already, since a key Porelo does not know is refused by name too; they now hold the bounds.
  - `GitGateTest`'s forge that takes the connection and never answers held the agent's push past the test's 60 s
    without the limit; with it the push ends within the gate's 2 s, saying the forge did not answer.
  - `GateServingTest` and `HostPushTest` failed without `--pass-on-within` and without the timeout branch.
  - Reading the line found four gaps: a timeout claimed nothing was passed on, `HostPush` left the mirror's branch
    set after a failure, its wait for the task's lock had no bound, and setting a ref back overwrote a push taken
    meanwhile. The new tests for them were run against the commit before the fixes. In `GitGateTest`, the forge
    that landed then stalled and the one that held nothing failed on the old message, and the ref taken meanwhile
    was deleted by the old set-back. In `HostPushTest`, the wait for another push of the task hung on the old
    unbounded lock until the JVM was ended.
  - The runner test also checks that the process is gone after its limit and which limit ended it.
    `UnattendedGitTest` holds that a push keeps its limit, and `GateServeHostPushTest` holds the bounds of
    `--pass-on-within`, and `HostPushTest` holds that a push after a wait for the lock has only what is left of the
    limit. `doc/running.md` and `doc/security.md` say that asking after a stalled push adds up to 30 s.
- **Locally on this commit.** `-pl apps/app -am install` (tests included), the whole reactor's `install -DskipTests`,
  the root's `validate` and `-Pdocuments test` are green.

## Found in play-through

Played on a test VM on 2026-11-13 by [B681](README.md#b681)'s script. The project had no `limits.pass_on`, so the
limit was the default 120 s. The forge's server was stopped and replaced on its port by a listener that takes the
connection and never answers. A push from the task then ended after **151 s** (150 s in the first run), with exit 1:

```
remote: the forge did not answer within 120 s; whether it took the push is not known - push again, which sets only this task's branch
 ! [remote rejected] HEAD -> refs/porelo/incoming/deliver (the forge did not answer within 120 s; whether it took the push is not known - …)
```

- **The bound.** The acceptance says the push ends within `limits.pass_on`, but it ends at `limits.pass_on` plus up
  to 30 s. In `GitGate.passOn` (`gate`) the push runs with `withTimeout(passOnLimit)`. When it ends at its limit,
  `holds()` asks the forge with `ls-remote` for up to `ASKING_AFTER` (30 s, or the limit when that is shorter), and
  only then answers `ForgeSilent`. `doc/running.md` already says that asking after adds up to 30 s, so either the
  acceptance is changed to say the limit plus the asking, or the asking fits inside the limit.
- **The words.** The acceptance asks for "nothing was passed on". `GateException.ForgeSilent` deliberately says
  "whether it took the push is not known" when the asking got no answer, and "did not hold the push when asked after"
  when the forge answered without the commit. The open choice is between the acceptance's "nothing was passed on" and
  the code's "not known", which is the truer statement for a forge that may have taken the push before it stalled.
