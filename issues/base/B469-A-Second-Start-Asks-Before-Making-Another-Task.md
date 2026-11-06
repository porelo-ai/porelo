# B469 — A Second Start Asks Before Making Another Task

**Status:** Idea.
**Size:** -

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

## Acceptance

- In a checkout with its own `project.yml` and one task of it, a start with no name at a terminal asks A or N; A attaches
  to that task, starting it first when it is stopped, and N makes a new task.
- With several tasks of the checkout, the question names them and A lets the person choose one.
- Without a terminal, a start with no name and a task already there starts or resumes that task and says so; a new task
  is made only when named. A start with a name keeps today's meaning.

## Open questions

- Without a terminal: resume the one task there, as proposed, or refuse and name both commands? And whether the numbered
  names (`-2`) then come only from N at a terminal.
