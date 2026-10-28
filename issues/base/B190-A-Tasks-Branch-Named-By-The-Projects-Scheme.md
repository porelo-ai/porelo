# B190 — A Task's Branch Named By The Project's Scheme

**Status:** Implementing.
**Size:** L - a project file block, the start's check and record, a typed refusal and an optional approval branch in
the daemon's contract, the gate's pass-on and its mirror refresh, the build watch, both approvals, and about 45 test
assertions and eight documents that name `porelo/<task>`.

**What must be true.** A project or one of its repositories says how a task's branch at the forge is named and which
names its forge accepts, so that an online task's work arrives as, say, `bugfix/porelo/login-fix` instead of
`porelo/login-fix`, a guarded task's approval goes there unless a person names another branch the forge accepts, and
work is never passed on onto a branch that already holds other work.

## Why

A forge commonly allows branches only under its own paths, one per kind of work - `feature/`, `bugfix/`, `hotfix/`,
`release/` or a team's own - with rules attached to each. Today the gate always names the branch `porelo/<task>`, so a
project on such a forge either loosens its rules for `porelo/*` or renames every branch by hand, which also loses the
builds a task is told about.

## The shape

- **Two keys** in the project's block, or a repository's, the repository's replacing the project's:

      branch:
        template: "{type}/porelo/{task}"     # default "porelo/{task}", today's name
        pattern:  "^(feature|bugfix|hotfix|release)/porelo/[a-z0-9-]+$"   # default "^porelo/.+$"

  The template builds the name from the values handed to the task ([B189](README.md#b189)) and the fixed `{task}`,
  `{project}`, `{repository}`; the pattern says which names the forge accepts. A regular expression alone could check a
  name but not make one. A template without a pattern is refused: the pattern is also what the gate keeps out of the
  mirror the agent fetches from.
- **`--branch <name>` at the start** names the branch outright, for a name no template makes; it must match `pattern`
  too.
- **Settled at the start and recorded with the task**: the name is built, checked against `pattern` and
  `git check-ref-format`, and kept; a resume and a later change to the project file leave it as it is.
- **Refused when it would move someone else's work**: in an online project, where the gate passes on with force, a
  branch that already exists at the upstream is refused at the start, as is a pattern that matches the upstream's
  default branch; two tasks of one repository with the same branch are refused in every class.
- **Used everywhere the name is now derived from the task's name**: the online gate's pass-on, the build watch that
  maps a pushed ref to a forge branch, the approval into a checkout, and `task status`.
- **A guarded approval goes to it too**: `gate approve <task>` with no branch named approves onto the task's branch
  instead of `main`, and a branch a person names must match `pattern`, so work reaches only branches the forge's rules
  cover. This holds in a project that has a `branch` block; one without approves as today, `main` by default and any
  branch named.
- **Nothing changes for the agent**: it still pushes to `refs/porelo/incoming/<task>` at the gate; only the name the work
  carries beyond the gate becomes the project's.

## Plan

**Today.** `porelo/<task>` is built in three places, each by cutting `GitGate.INCOMING` off the task's ref:
`GateServeCommand` for the online pass-on (`--pass-on`, started by `GateWiring.serving` and replayed from
`resume.json` on a resume), `BuildsWiring.branchAtTheUpstream` for the build watch's `--branch`, and
`CheckoutApproval.PREFIX` for work approved into a checkout that is its own upstream. `GitGate.refresh()` fetches every
head but `refs/heads/porelo/*` and drops what is left under it (`APPROVED`), so neither approved work nor another task's
forge branch reaches the mirror the agent fetches from. A guarded `gate approve` takes its branch at the approval,
`main` unless named, and refuses a branch holding other work with `BranchExists`. `TaskProfile.branch` in `task.json`
holds the gate's ref, not the forge's branch. `UpstreamProbe` already reads the upstream with `ls-remote` through the
gate's lending before a start, but keeps only whether it answered.

**Steps:**

1. Red first: a `GitGateTest` where an online task's work is passed on as `bugfix/porelo/task-1` and the mirror, after
   `refresh()`, holds no branch matching the project's pattern.
2. `core`: a record `BranchScheme` (template, pattern, defaults as today) on `Project` and `Repository`, read by
   `ProjectReader`, declared in `ProjectSchema`; `doc/project-file.md` and `DocumentedProjectFileTest`.
3. Start: the name from `Parameters.fill` of [B189](README.md#b189) or from `--branch` (`TaskRunCommand`, a `branch`
   in the daemon's `Start` and `CanStart`); `StartCheck` checks pattern and ref format with a new outcome
   `BRANCH_REFUSED`, and refuses a branch another task of the same project and repository holds (`TaskInventory`).
4. Record: a field `forgeBranch` in `TaskProfile` (version 5); a profile without it reads as `porelo/<task>`, so a task
   started before keeps its name. `task status` prints it and the daemon's `Task` carries it.
5. Online check: in `WorkspaceSetup.openWorkspace`, beside `gate.forget`, one `ls-remote --symref <upstream> HEAD
   refs/heads/<branch>` through the gate's lending, uncached; a branch that exists, or a pattern that matches the
   default branch, refuses with a typed error `BranchTaken` named in the contract like `EarlierWorkWaits`. A resume
   skips it: the branch is this task's.
6. Gate: `--branch <name>` on `gate serve` beside `--pass-on`, passed by `GateWiring.serving` from the record; absent,
   it is `porelo/<task>`, so a `resume.json` written before replays as it did. `GitGate.refresh()` drops the mirror's
   heads that match the project's pattern instead of `refs/heads/porelo/*`; `APPROVED` goes.
7. Build watch: `BuildsWiring.start` takes the recorded branch; `branchAtTheUpstream` goes.
8. Checkout approval: `CheckoutApproval` approves onto the recorded branch instead of `PREFIX + task`.
9. Guarded approval, in a project with a `branch` block: `GateApproveCommand` and the daemon's `Approve` and `Bundle`
   default to the recorded branch, read from the task's profile as `ReviewText` reads it, and refuse a named branch
   that does not match `pattern` with a typed error `BranchRefused`; `branch` in `Approve` becomes optional. The
   free name `nextFreeBranch` offers after `BranchExists` is offered only when it matches `pattern`. Red first: a
   `PoreloDaemonTest` that approves onto `main` in such a project and is refused.
10. Docs: `doc/project-file.md`, `running.md`, `commands.md`, `security.md`, `decisions.md`, `getting-started.md`,
   `pitch.md` (which says `gate approve` sends work as `porelo/<task>`, which it does not), and `WorkOrigin`'s text.
11. Acceptance: `task-builds.feature` reads the forge branch from the new `task status` line instead of rewriting the
    gate's ref with `sed`; a scenario in an online project with a template and `--set type=bugfix` whose push arrives
    upstream as `bugfix/porelo/<task>`, one refused at the start for a branch that exists, and a guarded one approved
    with no branch named that arrives under its recorded branch.

**Built, where it departs from the steps above:**

- The name is made by [B189](README.md#b189)'s `Parameters.fill` and judged by `BranchScheme` (`core`); `TaskBranch` (`app-task`) does
  both for the start and the daemon's `CanStart` alike. git's ref-name rules are checked in Java, so a dry run starts
  no process.
- `gate serve` reads the pattern from the project file it already takes with `--project`, and gets the branch with
  `--branch`; `GateSupport.gate` sets the scheme on every gate, so the mirror refresh, the restored default and the
  upstream's HEAD all judge task branches by it.
- The branch another task holds is checked just before the workspace opens, after the runtime is known to be there,
  and not in a dry run, which touches no container runtime.
- `MirrorFile` keeps reading `porelo/*` as task branches: the gate drops every branch the pattern matches before a
  mirror is read, so it needs no change.
- `ApprovalBranch` (`app-model`) is the one rule for `gate approve` and the daemon's `Approve` and `PendingBundle`. A
  project "has a branch block" when its scheme for the repository is not today's.
- `task status` shows the branch as `forge`, one word, so a script reads it with `awk`.

**Read against the skills, and what came of it:** `gate serve` judges a recorded branch only by git's rules, so a
task resumed after the project file changed keeps its branch; a restored bundle and a seed whose HEAD is a task
branch leave none in the mirror and HEAD on a branch it holds; under a project's own scheme a refresh asks for the
other branches by name, so a task branch is never in the mirror between a fetch and its removal; `CanStart` names a
task as Start does, from its repository or out of a container name, and leaves an unknown repository to the
repository check; `HEAD` is no branch name; `--branch` on a task brought back is refused like `--set`; a written block
is a block even when it says what the default says (`Project.declaresBranch`); `porelo approve` reads the record kept
over a restart; `BranchTaken` carries the settled task name and also types a branch another task here holds.
A second reading: the refresh under a scheme fetches before it removes what vanished, takes its refspecs on
standard input and names refs as git prints them; a mirror whose HEAD names a task branch or none it holds is put on
`main`, its only branch, or `main` unborn, which a start refuses naming the branches; the daemon's `RestoreGate` keeps
the scheme's task branches out as `gate restore` does; `CanStart` leaves the project's own repository of a project
with others to the repository check. **Declined:** an empty upstream with an unborn HEAD is not refused - a push with
force there replaces nothing; a recorded branch is not checked again on approval, since its start checked it; a
detached HEAD after the first clone is guarded but untested, since `git clone --bare` gives the mirror a branch even
from a detached upstream; `EarlierWorkWaits` naming the task as the call did predates this.

## Acceptance

- Seen to fail first, then green: each step's test above, in the module that carries it.
- With no `branch` block, every existing test that names `porelo/<task>` passes unchanged.
- An online start whose branch exists upstream creates nothing and names the branch and how to choose another.
- A task started before the change and resumed after it passes on under `porelo/<task>`, and approves as before.
- In a guarded project with a `branch` block, an approval onto `main` is refused and names the pattern.

## To be checked

- That `git fetch` before `refresh()` drops the matching heads cannot be read by an agent fetch in between: today
  `GateServeCommand` refreshes before it serves each fetch, which would keep it so; confirm in `GitHttpServer`.
- The build readers key their answer by branch (`StubReader`, `builds/stub/README.md`): that a branch with slashes
  such as `bugfix/porelo/x` reaches the forge's API escaped.
