# B329 — A Forced Removal Kills What Outlives Its Stop

**Status:** Idea.

**What must be true.** `porelo task remove --force` ends a task's helpers and what they started, even a process that
ignores SIGTERM. A process that has already exited and only waits to be reaped (a zombie) counts as stopped. "still
running" and exit 70 are left for a process that is still alive after SIGKILL.

Seen in Agent Smith's acceptance run on snapshot.352 (2026-10-29): after `claude -p` or `pi --print` in the attached
shell, `porelo task remove porelo-live-shell --force` exited 70 with "still running: <pid> ?". The empty command is
what a zombie shows. Today a stop sends SIGTERM to the helpers and their descendants, waits 2 s, and reports whatever
still answers alive; `--force` changes none of that.

## Acceptance

- After the wait, `--force` sends SIGKILL to every helper and descendant still alive, then checks again.
- A process in state Z is not reported as running.
- A removal without `--force` keeps today's behaviour: SIGTERM, wait, report.
- The three acceptance legs of the live shell that end in `task remove --force` pass.
