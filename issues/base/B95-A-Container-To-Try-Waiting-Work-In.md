# B95 — A Container To Try Waiting Work In

**Status:** Idea. The operator's decision: the agent builds and tests its work in its own task, and the forge's
build checks it apart from the agent; the person's review at the gate is a review of the code. Running the same
build once more in a container of its own is not planned.
**Size:** XL - a command, a streaming Varlink method, a third kind of container and an IDE; splits in two below.

**What must be true.** A reviewer can run a build or tests against waiting work, and later attach an
IDE to it, in a fresh container with the project's egress and no way back - never on the host and
never in the agent's own container - and see the result beside the review.

## Why

Reviewing work often means running it: its build, its tests. Run on the reviewer's computer, or on the machine as
the account that holds the vault and the daemon, that executes the agent's unreviewed code with rights the box
exists to keep from it. Run in the agent's own container, it runs in an environment the reviewed work could have
prepared - a cached plugin, a wrapper, `PATH`.

The interface's half is porelo-frontend's: "Run its tests" and "Open in an IDE" beside *Approve*.

## The shape

1. **`porelo gate try <task> -- <command>`** runs a command against the waiting work in a fresh container from the
   project's image, with the waiting ref as its workspace, the project's egress and limits, and no way back to the
   gate.
2. **Its output and exit are kept and shown beside the review**, in the terminal and over the daemon.
3. **The container is removed when the command ends**, and nothing it wrote reaches the mirror.
4. **Later, the same container for an IDE to attach to**: the IDE's backend inside it (installed through the
   project's image, not downloaded at run time), reached through podman, never on the host.

### Not offered

**An IDE's remote backend on the host.** It runs as the account that holds the vault, the daemon's socket and the
deploy keys; building there runs the agent's code with all of it.

## Plan

Read in the code on 2026-10-11. A task's container is made in one place, `TaskRunner.start`
(`apps/app-base/.../TaskRunner.java`): the ruleset and resolver written to the task's state directory, the
sidecar the hooks read, the image built under the project's build lock, and a `ContainerSpec`
(`runtime/.../ContainerSpec.java`) with the project's limits, the vault socket, the mailbox and the guide. The
gate's calls (`35-gate.varlink`, `GitGate` in `gate/`) name pending work by project, repository and ref, and
`Approve` pins the commit the person read (`commit`).

**Split in two**, each L or smaller:

1. **This issue: `gate try`** (L), in the terminal and over the daemon.
2. **A new issue: the IDE in a try container** (L), after this one and beside [B100](README.md#b100), which decides how a person's
   computer reaches something inside a container through ssh. Its backend comes through the project's image;
   this issue only keeps the container's shape fit for it.

Steps for `gate try`:

1. **`TryRun`** in `apps/app-gate`: the pending ref's commit, taken as `Approve` takes it (refused as
   `MovedSinceReview` when it moved), exported from the mirror with `git archive` into a directory of the
   account's own under the project's state - never a checkout the mirror shares.
2. **A container like a task's, without a way back**: `TaskRunner`'s ruleset and resolver with no gate address
   (the online and guarded classes put the gate in the ruleset; here nothing does), no vault socket, no mailbox,
   no guide, the project's image and `limitsFor(repository)`. Labelled as Porelo's with a kind of its own, so
   `porelo task list` never shows it as a task and `poreloContainers()` still finds it to clean up. Removed when the
   command ends, `--rm` and a `finally`.
3. **`porelo gate try <task> [--repository] -- <command>`** (`GateTryCommand`), its output streamed, its exit the
   command's.
4. **`method Try(project, upstream?, repository?, name, commit?, command: []string)`** in `35-gate.varlink`:
   streaming, a reply per chunk of output (`lines`, as `Tail` sends them) and a last one with `exit`. Refused as
   `MovedSinceReview` as `Approve` is, and `StreamRequired` without `more`. The interface's "Run its tests" calls it.
5. **The command a project names**: `repositories.<name>.try` in `project.yml`, a list of arguments run without a
   shell, e.g. `try: ["./mvnw", "-B", "verify"]`; `gate try <task>` with no command after `--` runs it, and the
   interface offers it, a person's own override aside. `ProjectFile`'s reader and `doc/project-file.md` take the
   key; a string instead of a list is refused by name.
6. **The result kept with the review**: the last run's command, exit, commit and when, beside the pending ref's
   record, and `Review` answers it (`tried: ?TryResult`).

Red first: a unit test of the container's specification that finds the gate's address in its ruleset, or a
vault socket among its volumes. Then the acceptance scenario in `gate-review.feature`: a command that pushes
to the gate, one that reaches a host outside the egress, the container listed after the end, the mirror's refs
compared before and after.

## Acceptance

- `porelo gate try <task> -- <command>` runs the command in a fresh container from the project's image, with the
  waiting ref as its workspace and the project's egress and limits.
- The container cannot push back to the gate.
- Its output and exit are shown beside the review, in the terminal and over the daemon.
- The container is gone when the command ends, and the mirror is unchanged.
- An IDE's backend is reached only inside such a container, through podman; nothing of it runs on the host.
- **Seen to fail:** a test whose command pushes to the gate goes red when the push arrives; one whose command
  reaches a host outside the project's egress goes red when it succeeds; one that lists containers after the
  command ends goes red when it is still there; one that compares the mirror before and after goes red when it
  changed; one that reads the review goes red when the output and exit are missing.

## To be checked

1. **Its limits**: the task's, or a review's own - a build or an IDE backend wants more memory than many tasks
   (the operator's).
2. **Whether an approval records which run it followed**, beyond the review showing the last one (the operator's).
3. **Whether `gate try` takes the agent's provider credentials** - a test suite that calls a model needs them, and
   the plan above gives none (the operator's).

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F7,
answering the source document's "same SAST/DAST gates for AI code" (§3), Checkmarx, LinkedIn 11.
