# B741 — An Agent Never Replaces What Starts It

```json
{
  "$schema": "issue-1.json",
  "id": "B741",
  "title": "An Agent Never Replaces What Starts It",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 50,
  "blockedBy": [],
  "covers": "Every agent's start runs its binary by an absolute, root-owned path and refuses one the agent could change; each adapter installs its binary as root, as pi does.",
  "openQuestions": 0
}
```

**Why this size.** S, Porelo's part only: an artifact a root step owns, and the binary's path fixed at build and
checked at each start. The adapters' part is theirs. Estimate: 45 minutes (Core's median for S).

**What must be true.** What an agent writes during a task never picks what Porelo starts as that agent next time.

Carried on from [B738](README.md#b738), which closed the start files and `PATH`. Read in the agent repositories:

| Agent | Where its binary is installed | Owned by |
|---|---|---|
| pi | `/usr/local/bin/pi` (`as_root`) | root |
| stub | `/usr/local/bin/porelo-stub-cli` | root |
| claude | `$XDG_DATA_HOME/claude/versions/<v>`, linked from `$XDG_BIN_HOME/claude` | the agent |
| omp | `$XDG_BIN_HOME/omp` | the agent |
| codex | `$XDG_BIN_HOME/codex` and `codex-code-mode-host` (`as_agent`, `install -D`) | the agent |
| agy | `$XDG_BIN_HOME/antigravity`, with an `agy` wrapper | the agent |

For the last four, the agent can overwrite its own binary, so the next start runs what the agent wrote, whatever
path names it. `AgentDefinition.binary` is a bare name that each start looks up on `PATH`, in `buildCommand` and
`attendedCommand`.

## Plan

The operator decided on 2026-11-13: each adapter installs its agent's binary as root, as pi does, and Porelo does not
chown an agent's install. What stays Porelo's:

- **An artifact a root step owns** (agents/api and the definition format; asked for by Smith, 2026-11-13): an
  artifact may say `owner: root` (default `agent`, as today). `Agent.imageLayer()` renders the root ones, with the
  same digest check, at the start of the root fragments, before `as_root`, so a root fragment can unpack them. The
  rest are rendered as today. The reader refuses any other value, and a root artifact whose target is under
  `/home/agent`. `porelo agents --supply-chain`, `unverifiedArtifacts()` and the adapters' pin checks see a root
  artifact as any other. Red first in agents/api: a definition with a root artifact renders its download and check
  before `as_root`, as root; `owner: nobody` and a root target under `/home/agent` are refused.
- **At build:** after the agent's layers, Porelo resolves `AgentDefinition.binary` on root's `PATH` once. It records
  the absolute path and its resolved target in the image, in a root-owned file `/etc/porelo/binary/<binary>` (0644,
  its directory 0755), written by a `RUN` as root after every layer. Not a label: a `LABEL` holds only what the
  Containerfile spells, and this is known only once the layers above have run. The start reads the file in the same
  root exec that checks it. A binary that is not on `PATH` after the layers fails the build; a binary name that is
  not a bare program name is refused before the build. A target that starts with `#!` has its interpreter recorded
  too: `#!/usr/bin/env NAME` resolves NAME on root's `PATH`, an absolute interpreter keeps its one optional
  argument; an `env` line with options or more than one word, a relative interpreter and an interpreter that is
  itself a script fail the build.
- **At each start, unattended or attached:** Porelo runs that absolute path, never a bare name, and for a script
  `[interpreter, its argument, path, ...]`, never the kernel's `#!` lookup. It refuses the start when the path or
  the interpreter, any link either passes through, its resolved target, any directory any resolution of any of
  them passes through (every prefix resolved, so a directory link in the middle is checked where it leads), with
  every directory above it, or the record itself can be written by the agent's user, or is owned by it; a script
  whose record names no interpreter is refused as unrecorded; also when the path no longer resolves to the
  recorded target, when the image records nothing (a container made before this, whose image cannot change: its work is kept, and a new task's start builds an image that records it, since every start builds with podman's cache and the record is a new layer at the end), and when
  the check could not answer. Writable is asked of the kernel as the agent (`setpriv --reuid=agent --regid=agent
  --init-groups test -w`), from root in the container. The refusal names the agent and the path, and says the
  adapter must install it as root. An agent command that does not start with its binary is refused too.
- First red, in `runtime` and `apps/app`: the rendered Containerfile records the binary's resolved path; a start
  whose agent's binary the agent's user can write (`$XDG_BIN_HOME/<name>` of the agent) is refused at start, with that
  message. A root-owned `/usr/local/bin/<name>` starts by its absolute path.

**What each adapter must deliver** (Smith files these in the adapters' repositories):

| Adapter | Today | Must deliver |
|---|---|---|
| claude-code | `$XDG_DATA_HOME/claude/versions/<v>`, linked from `$XDG_BIN_HOME/claude` | the version and its link under a root-owned prefix (e.g. `/usr/local/lib/claude`, `/usr/local/bin/claude`), written in `as_root`; self-update off or failing harmlessly |
| omp | `$XDG_BIN_HOME/omp` | `omp` in `/usr/local/bin`, written in `as_root` |
| codex | `$XDG_BIN_HOME/codex` and `codex-code-mode-host`, `install -D` in `as_agent` | both in `/usr/local/bin` (or one root-owned directory), installed in `as_root`; the helper found by codex there |
| agy | `$XDG_BIN_HOME/antigravity` and an `agy` wrapper | both root-owned under `/usr/local`, written in `as_root` |

Until an adapter delivers, its agent's start is refused with the message above, so the release that ships this waits
for the four adapters or carries them.

## Acceptance

- For each of the six agents, the file a start runs, and what it execs, cannot be changed by the agent's user.
- A start whose agent binary the agent's user could change is refused, naming the path.
- An agent that updates itself in a task fails to write over it, and the next start runs the built version.

## Answered

- Each adapter installs its agent's binary as root, as pi does; Porelo does not chown an agent's install. Porelo's
  part is the absolute, root-owned path and the refusal (the operator, 2026-11-13).

## Built

- `runtime`: `PinnedBinary` (the Containerfile lines that resolve and record the binary as root after every layer,
  the root check script, and the typed answer), `ImageLayers.pinning(binary)`, `Containerfile` rendering it last,
  `Podman.pinnedBinary(container, name)` running the check through `exec(ROOT_USER, ...)`.
- `apps`: `AgentBinary` (app-base) with the named refusal `AgentBinary.Refused(agent, reason, path)`; the
  unattended run (`TaskLaunch.runAgentSteps` → `TaskRunner.runAgent`), the attended session
  (`TaskSession.attachedAgent`, from `task attach` and a `task start` that goes in) and `task start --attach agent`
  check first and run the absolute path; `TaskLaunch` and `Preparation` pin the selected agent's binary in the image.
  The login image is not pinned: it is no task start.
- Seen red then green: `PinnedBinaryTest` (runtime, six tests: the record rendered as root after the agent's layers;
  a name a shell would read refused; the check run as root and its path run; a writable path refused naming it; an
  empty answer refused; a command starting with another program refused), red with the code broken on purpose,
  green restored. `TaskRunCommandTest.anAgentWhoseBinaryItsOwnUserCouldWriteIsNotStarted` and
  `anAgentWhoseBinaryRootOwnsStartsByItsAbsolutePath` (apps/app), likewise.
- Upgrade: `TaskRunCommandTest.aNewTasksStartBuildsTheRecordOfItsAgentsBinaryIntoAnImageBuiltBeforeIt`, red with the
  pin left out of `TaskLaunch`, green restored. An existing stopped task is refused with `unrecorded`, naming the
  binary, and keeps its container.
- In a real container: `PinnedBinaryInContainerTest` (runtime, opt-in with `-Dporelo.test.pinned.binary=true`, local
  `ubuntu:24.04`): a root-owned `/usr/local/bin/demo` pinned; an agent-owned `$XDG_BIN_HOME/demo2`, a root-owned link
  to it and a root-owned binary in a `/usr/local/bin` the agent's group may write refused; an unrecorded name refused.
- The reading (agy gemini), fixed, each seen red then green:
  - directory links in the middle of the path: every prefix of every hop and target is resolved and walked up
    (`PinnedBinaryInContainerTest.aBinaryReachedThroughADirectoryTheAgentCanRenameIsRefused`,
    `PinnedBinaryTest.theCheckLooksAtEveryDirectoryAResolutionOfThePathPassesThrough`);
  - the interpreter: recorded at build, checked as the binary is, run in front of the script
    (`PinnedBinaryInContainerTest.aScriptRunsThroughTheInterpreterItsImageRecordedAndOneTheAgentCouldSwapIsRefused`:
    an agent-owned `env` interpreter refused, a root-owned one and `#!/bin/sh -e` run through their recorded paths,
    `env -S` and a script interpreter fail the build; `PinnedBinaryTest`: the words run, a stray line refused);
  - the attended paths: `task start --attach agent` and going into a task whose session is gone, each run by the
    absolute path and each refusal entering nothing (four tests in `TaskRunCommandTest`).
- The reading, declined: the ptrace race (Yama `ptrace_scope` 1 is the default on both supported distributions, so
  the agent's earlier process cannot attach to the check's shell, which is no descendant of it); the agent's own
  config hooks under its home (they run as the agent, with what the agent already has).
- Not run: the acceptance suite.
