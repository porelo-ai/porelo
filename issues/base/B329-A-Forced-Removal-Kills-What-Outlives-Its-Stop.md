# B329 — A Forced Removal Kills What Outlives Its Stop

**Status:** Review.
**Size:** S - a second, forced round after the wait in the one place a task stop counts its survivors, and a zombie
told apart from a living process; tests with real processes.

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

## Plan

- A process counts as running only when it is alive and not in state Z (read from `/proc/<pid>/stat`; where there
  is no `/proc`, alive is running).
- The helpers' descendants are taken before the stop, as the stop itself takes them.
- With `--force`, after the 2 s wait every helper and descendant still running gets SIGKILL, then another wait of
  up to 2 s; only then are survivors counted. Without `--force`, nothing changes but the zombie rule.
- Tests start real processes: a helper that traps SIGTERM (named without `--force`, ended with it) and a helper
  that becomes a zombie under a parent that never reaps it (not named).

## To be checked

- A stop without `--force` still counts only the helpers themselves as survivors, as the acceptance keeps today's
  behaviour: a helper that ends on SIGTERM while a child of it ignores SIGTERM is reported clean. Counting the
  children there too would turn such stops into exit 70; that is a decision of its own.
