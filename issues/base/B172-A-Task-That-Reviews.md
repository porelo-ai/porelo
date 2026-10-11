# B172 — A Task That Reviews

**Status:** Analyzed; blocked by sokar [B166](README.md#b166).
**Size:** M - a role in the task's record, the gate's per-task server read-only for it, [B166](README.md#b166)'s origin, a start option,
docs and an acceptance step; no contract beyond [B166](README.md#b166)'s.

**What must be true.** A task can start in the role reviewer (way D). It receives what it reviews through its
repository, read-only, and writes comments - into the local review and, optionally, on a forge's pull request through
[B171](README.md#b171). It has no approve, no reject and no push on what it reviews.

## Why

An agent that reviews another's work, or a person's, is useful before a person looks; the two stay apart only if the
reviewer can write nothing but comments. [B166](README.md#b166)'s model gives it a place to write them, and the gate already holds what
it reviews.

## The shape

- `sokar task start --role reviewer` with what to review: waiting work, a branch, or a pull request's head.
- A reviewer reviews only other tasks of its own project: never its own work, never another project's.
- The gate puts those commits into the task's mirror, read-only. The task's pushes are refused.
- Its comments appear in the review beside the person's, marked with their origin (task and role), through [B166](README.md#b166)'s
  checks; on a forge, through [B171](README.md#b171)'s release.
- Gate operations - approve, reject, push - are refused to a reviewer task, by role, on the host.

## Plan

A task reaches the gate only through its own git server (`gate/.../GitHttpServer.java`), which serves `upload-pack`
with every other task's incoming ref hidden (`hidingOthers`) and takes a push to the task's own ref only. Approve and
reject are the person's, through the CLI and the daemon, which a task has no way to call. So the role changes what
that server shows and takes, and what origin the host gives its comments.

1. **Red first:** a test in `gate` that a server made for a reviewer answers a `receive-pack` with a refusal in git's
   own protocol, and shows the reviewed ref in its advertisement.
2. **The role in the task's record:** `wire/.../TaskProfile.java` gains `role` (`developer` by default, `reviewer`),
   with a version bump as for the provider; `sokar task status` and `task list` show it.
3. **The start:** `--role reviewer --review <pending name>` (or `--review-branch <branch>`) in the start options,
   checked by `StartCheck`: the pending push must exist in the same project and be another task's; one of another
   project is refused as not found, naming no other project. The pull request's head is [B171](README.md#b171)'s step
   and comes with it.
4. **The gate:** `GateWiring` makes the task's server with the reviewed ref unhidden and `receive-pack` refused
   whatever the ref; the task's clone checks out the reviewed commit. The workspace setup gives no push URL.
5. **The comments:** [B166](README.md#b166)'s dispatch of `to: review` sets the origin from the record (`agent`, the task, `reviewer`)
   and accepts a thread on the reviewed push only; the guide (`MailboxGuide`) tells a reviewer it can write comments
   and nothing else.
6. **Docs:** `doc/commands.md` says the role and what it cannot do.
7. **Acceptance:** a scenario in `acceptance/suite/.../gate/gate-review.feature`: a stub developer task pushes, a
   stub reviewer task fetches the work, its comment shows in `gate comments` as the reviewer's, and its push is
   refused.

## Acceptance

- Seen to fail first, then green: a reviewer task reads the waiting work, its comment shows in the review as the
  reviewer's, and its push and an approve in its name are refused.
- A reviewer started on another project's waiting work is refused before it starts.
- `doc/commands.md` says the role.

## To be checked

- What "an approve in its name" in the acceptance means when no task can call approve: a test that the task's
  channels carry none, or nothing to test (Agent Core's).
