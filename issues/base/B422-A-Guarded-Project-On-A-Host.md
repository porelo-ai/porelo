# B422 — A Guarded Project On A Host

**Status:** Idea.
**Severity:** medium: without a `branch` block, work approved onto `main` at a host's gate cannot reach the host's own forge, and
only the push's refusal says so.

**What must be true.** A guarded project on a host has a way to hand its work to the host's forge, or it is refused before a task
starts, in words naming why.

**What holds today.** Seen on a test host at `c7293243`, in a project without a `branch` block: a guarded task's box has no way
out, and `gate approve` pushes to `main`, which the local forge refuses as a protected branch. So `project init --upstream` at a local forge writes
`online` for now, with the project's upstream. That is a security class chosen for the person, and it stands in the draft for them to see.

This holds only without the block, and only on `main`. With the block, `gate approve` goes to the task's own branch
(`doc/project-file.md`, `GateApproveCommand`); without it, `--branch` names another. Both read in the code, neither run on a host.

An `online` box on the same host pushes `porelo/<task>` to the local forge with a task's token, as seen with `--host-push`.

**To be checked.**

- Whether `guarded` with a `branch` block works on a host; that has never run.
- A stopped task made before a row lost its `central`, or before that rule: `task start` resumes it through `TaskControl`, which does
  not ask the rule, read in the code. Whether a resume should be refused too.
