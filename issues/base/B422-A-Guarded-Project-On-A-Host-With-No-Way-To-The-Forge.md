# B422 — A Guarded Project On A Host With No Way To The Forge

**Status:** Idea.
**Severity:** medium: work approved at a host's gate cannot reach the host's own forge, and only the push's refusal says so.

**What must be true.** A guarded project on a host has a way to hand its work to the host's forge, or it is refused before a task
starts, in words naming why.

**What holds today.** Seen on a test host at `c7293243`: a guarded task's box has no way out, and `gate approve` pushes to `main`,
which the local forge refuses as a protected branch. So `project init --upstream` at a local forge writes `online` for now, with
the project's upstream. That is a security class chosen for the person, and it stands in the draft for them to see.

An `online` box on the same host pushes `porelo/<task>` to the local forge with a task's token, as seen with `--host-push`.

**To be checked.**

- Whether the gate's push on a host should take a branch of the task's name instead of `main`, and who then opens the pull request.
- Whether `gate approve --upstream` on a host is held to the rule that a repository row with no `central` is refused, as a task's
  start is. It is not, read in the code.
