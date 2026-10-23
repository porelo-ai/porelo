# B267 — A Mirror Without A Default Branch Is Named

**Status:** Idea.

**What must be true.** When a repository's mirror names no default branch, a task start on it says so, naming the
repository and how to fix it, rather than going on as if the repository had no `porelo.yml`.

## Why

Seen on the shared VM on 2026-10-23: a project followed from a bare clone whose `HEAD` named `master`, while its only
branch was `main`, started its task with the project's own image. The repository's `porelo.yml` was never read, and
nothing said so. A refusal that was expected did not come, and the image was not the one the repository asks for.

Read in the code: `ImageChoice` asks `MirrorFile.defaultBranch` for the mirror's branch, and reads `porelo.yml` only
when there is one (`apps/app-model/.../ImageChoice.java:172-188`). With no branch it adds nothing, so the repository's
image quietly gives way to the project's.

## To be decided in the analysis

- Refuse the start, or start with the project's image and say which file was not read and why.
- Whether `project follow` should already warn of a mirror with no default branch.
