# B469 — A Second Start Asks Before Making Another Task

**Status:** Review.
**Size:** S - the checkout's tasks found for a checkout with its own project file, one question in the start, their
tests and one line of `doc/commands.md`; no contract.

**What must be true.** `porelo task start` with no name, in a repository that already has a task, never quietly makes
another one. At a terminal it asks, in substance, "`<task>` is already running - attach to it (A) or start a new task
(N)?": A opens that task, starting its container first when it is stopped, and N makes a new one. Without a terminal it
does the one thing the person can predict and says which.

## Found

Started several times in a row with no name in the same repository, `porelo task start` made a new task each time, its
name numbered `-2`, `-3` and on.

Read in the code, not run: with no name, the start asks `TaskTarget.fromCheckout` for the tasks of this checkout. That
method knows the checkout's project only when the checkout is a repository of `default`, or when its origin is placed
under a project on this machine. A checkout that carries its own `project.yml`, as one does after a first start wrote
it, is neither, so the start finds no task and `TaskNames.fromRepository` picks the next free name.

## Plan

- **Find the checkout's tasks.** `TaskTarget.fromCheckout` also knows the project of a checkout whose own `project.yml`
  this machine approved: it reads the project's name in the checkout's `project.yml` and takes it when this machine
  holds an approved copy of that project, the repository being the checkout's own; read only, never adding anything to
  `default`. That one change also lets `task attach`, `task logs` and every other task command
  find the task of such a checkout with no name, which today they refuse.
- **Ask at a terminal.** In `TaskRunCommand`'s branch for a start with no name, where one task of the checkout is there,
  the start asks "`<task>` is running - attach to it (A) or start a new task (N)?" (`stopped` where it is); A takes that
  task, which the start resumes and attaches to as it does a named one, and N names a new one with
  `TaskNames.fromRepository`, as today. With several, the question lists them, A asks which, and N names a new one.
- **Without a terminal** (`--no-input`, no terminal, `--yes`, `--dry-run`): one task there is resumed and the line
  says so, as today for a checkout whose tasks were found - it never makes a second task unasked; several are refused, naming them and `porelo task start
  <name>`; a new task comes only from a name. The numbered names (`-2`) then come only from N.
- **Red first:** in `TaskLifecycleCommandsTest`, a checkout with its own approved project file and one task: a start with
  no name and no terminal takes that task, where today it names `-2`; with a terminal answering N it names `-2`, and
  answering A it takes the task. `TaskTargetTest`: `fromCheckout` answers that task.

## Acceptance

- In a checkout with its own `project.yml` and one task of it, a start with no name at a terminal asks A or N; A attaches
  to that task, starting it first when it is stopped, and N makes a new task.
- With several tasks of the checkout, the question names them and A lets the person choose one.
- Without a terminal, a start with no name and a task already there starts or resumes that task and says so; a new task
  is made only when named. A start with a name keeps today's meaning.

## To be checked

- Whether an interface starts a task with no name through the daemon (`StartCheck` names one with
  `TaskNames.fromRepository`); if so, it asks the same question in its own window, from the tasks the daemon lists.
