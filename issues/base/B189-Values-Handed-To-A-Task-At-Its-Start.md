# B189 — Values Handed To A Task At Its Start

**Status:** Backlog.
**Size:** L - a new project file block in `core`, four start options in the CLI, three types and a refusal in the
daemon's contract, a durable record, the container's environment, the guide, `task status` and an acceptance feature.

**What must be true.** A project declares the values a task of it can be started with, and the person starting a task
hands them in on the command line, from a file or from their shell's environment; each is checked against its
declaration before anything is created, fixed for the task's whole life, shown with where it came from, and offered to
the agent and to the rest of the project's settings by name.

## Why

More than one setting wants to differ from task to task without a project of its own for each: the kind of work that
names a task's branch at the forge ([B190](README.md#b190)), a ticket for a commit
trailer, a label at the forge. One way of handing values in serves them all; a separate option for each would grow the
start command without end and check each value differently.

## The shape

- **Declared in `project.yml`, by the project only**, so a typo is refused rather than ignored and every repository of
  a project takes the same values; a repository that needs its own branch names has its own `branch` block instead
  ([B190](README.md#b190)):

      parameters:
        type:
          description: "The kind of work"
          values: [feature, bugfix, hotfix, release]   # a fixed list, or
          default: feature
        ticket:
          description: "The ticket in the tracker"
          pattern: "[A-Z]+-[0-9]+"                     # a regular expression
          required: false
          env: "TASK_TICKET"                           # default SOKAR_PARAM_TICKET; 'none' keeps it out of the task

  A key no declaration names is refused, as is a value outside `values` or `pattern`, and a missing `required` one.
- **Handed in at the start** in four ways:
  - `--set type=bugfix` - the value itself;
  - `--set type` - the value of the declaration's environment variable in the calling shell;
  - `--set-file params.yml` - values from a file, repeatable, the last file winning;
  - `--set-env` - every declared variable present in the calling shell.

  Nothing is taken from the environment without `--set <name>` or `--set-env`: a variable left in a shell from
  yesterday must not slip into today's task and name its branch at the forge. Where a value is given more than once,
  `--set` wins over a file, a file over the environment, and the environment over `default`.
- **Read where the command runs**, by the CLI, not by the daemon: the environment and files are the caller's. The
  daemon's start takes values already read, each with its origin as the client says it.
- **Checked before anything is created**: a value that fails its declaration refuses the start, naming the
  declaration and what it allows.
- **Fixed for the task's life**: recorded with the task's other data when it is created; a `task start` that resumes a
  task refuses `--set`, `--set-file` and `--set-env`, and a later change to the project file does not reach a task
  already started.
- **Shown with its origin** in `task status` and the daemon's `Task`, as `type=bugfix (environment)`, so a branch's
  name can be traced back.
- **Seen by the agent, never changed by it**: each value is set in the container under its declaration's `env`,
  `SOKAR_PARAM_<NAME>` by default, and named in the task guide; `env: none` keeps one out. The variable a value is read
  from in the caller's shell has the same name as the one the agent sees.
- **Used by name in other settings** as `{name}`, beside the fixed `{task}`, `{project}` and `{repository}`.
- **Not secrets**: values stand in the task's record, listings and environment. The documentation says so and points
  credentials to the vault.

## Plan

**Today.** `project.yml` is read by `ProjectReader` into the record `Project`, one private method per block, and
checked against `ProjectSchema` (`SECTIONS`, `KEYS`, `check()`); `DocumentedProjectFileTest` parses the example in
`doc/project-file.md` and fails on a key the schema does not know. `task start` is `TaskRunCommand`, which builds a
`TaskLaunch.Request` in its own process; the daemon's `Start` and `CanStart` (`15-task.varlink`) build the same request
in `SokarDaemon.request()`. `StartCheck` settles everything before anything is made and answers a `StartOutcome`. The
credentials a start names are the precedent for everything here: a repeatable `Map` option, a `credentials` field in
`Start`, `credentials.json` in the task's state directory, listed in `TaskState.DURABLE` so it outlives a reboot, read
back by `TaskInventory.describe` into the daemon's `Task`. `SOKAR_ISSUE_PREFIXES` is the precedent for a value put
into the container's environment (`TaskLaunch.launch`). A container's environment is fixed when it is created, so a
resume cannot change a value even by mistake; only the record, the guide and `{name}` need it.

**Steps:**

1. Red first: `ProjectParametersTest` in `core`, after `ProjectBuildsTest` - a declaration with `values`, one with
   `pattern`, `default`, `required`, `env`, `env: none`; refused: both `values` and `pattern`, a `default` outside them,
   a name that is not lower-case letters, digits and hyphens, an `env` that is no variable name, two declarations with
   one `env`, and `parameters` under a repository.
2. `core`: a record `Parameter` and `Parameters` with `resolve(given)` - precedence, defaults, the refusal text; a
   field on `Project` through its constructor chain; `parameters(...)` in `ProjectReader`; `parameters` and
   `parameters.<parameter>` in `ProjectSchema` with its `KEYS` and a loop in `check()`; the example in
   `doc/project-file.md` and its assertion in `DocumentedProjectFileTest`.
3. `{name}` substitution as one method in `core` (`Parameters.fill(template, task, project, repository)`), refusing
   an unknown name; its first user is [B190](README.md#b190).
4. CLI: `--set`, `--set-file`, `--set-env` on `TaskRunCommand`, read there with each value's origin (`command line`,
   `file <path>`, `environment`, `default`); a `parameters` component on `TaskLaunch.Request`, carried also by
   `withTask()`. The resumed path (`startExisting`) refuses any of the three options.
5. Check: `StartCheck.parameters(...)` in the `check` chain with a new outcome `PARAMETER_REFUSED`, so `CanStart` and
   `Start` cannot disagree; called again in `TaskLaunch.launch` before the dry run. Tests in `StartCheckTest` and
   `TaskRunCommandTest`.
6. Daemon: `parameters: ?[string]Given` (value and origin) in `Start` and `CanStart`, `PARAMETER_REFUSED` in
   `StartOutcome`, `parameters: [string]Given` in `Task`; `InterfaceDescriptionTest` holds the names to the contract.
7. Record: `parameters.json` beside `credentials.json`, in `TaskState.DURABLE`; read by `TaskInventory.describe`;
   shown by `TaskStatusCommand` one line per value with its origin.
8. Agent: each value with an `env` into the container's environment next to `SOKAR_ISSUE_PREFIXES`; the guide names
   them in a part after the text every task shares, so a provider's cache of the shared beginning still holds;
   `TaskLaunch.informed()` gets the recorded values so a resume writes the same guide.
9. Docs: `doc/project-file.md`, the `task start` options in `doc/commands.md`, a section in `doc/running.md`.
10. Acceptance: `task/task-parameters.feature` after `credential-several.feature` - a value from each of the four
    ways seen in `podman exec {task} env` and in `List`; a value outside its declaration refused with nothing created;
    a resume with `--set` refused.

## Acceptance

- Seen to fail first, then green: each step's test above, in the module that carries it.
- A start with a value outside its declaration creates no container, no state directory and no gate, through the CLI
  and through the daemon.
- After a reboot, `task status` shows the same values and origins.
- `doc/project-file.md` and `doc/commands.md` say it; the documents' tests are green.

## To be checked

- `TaskLaunch.Request.withTask()` passes 17 of 18 components and drops the credentials when a container name is
  adopted as a task name: confirm with a test and fix it in the same change, since the new component goes the same
  way.
- `TaskInventory` still names `sokar task resume`, which is no command any more: whether the hint the plan's refusal
  gives should name `task start` instead.
- Whether the interface in `sokar-frontend` offers the values at a start: an issue there, raised in the channel once
  the contract is settled.
