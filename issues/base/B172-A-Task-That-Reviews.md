# B172 — A Task That Reviews

**Status:** soon, after B166.

**What must be true.** A task can start in the role reviewer (way D). It receives what it reviews through its
repository, read-only, and writes comments - into the local review and, optionally, on a forge's pull request through
B171. It has no approve, no reject and no push on what it reviews.

## Why

An agent that reviews another's work, or a person's, is useful before a person looks; the two stay apart only if the
reviewer can write nothing but comments. B166's model gives it a place to write them, and the gate already holds what
it reviews.

## The shape

- `sokar task start --role reviewer` with what to review: waiting work, a branch, or a pull request's head.
- The gate puts those commits into the task's mirror, read-only. The task's pushes are refused.
- Its comments appear in the review beside the person's, marked with their origin (task and role), through B166's checks;
  on a forge, through B171's release.
- Gate operations - approve, reject, push - are refused to a reviewer task, by role, on the host.
- Size: medium.

## Acceptance

- Seen to fail first, then green: a reviewer task reads the waiting work, its comment shows in the review as the
  reviewer's, and its push and an approve in its name are refused.
- `doc/commands.md` says the role.

## To be checked

- Whether a reviewer may review its own project's other tasks only, or any waiting work on the machine.
