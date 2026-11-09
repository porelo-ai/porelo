# [B553](README.md#b553) — A Move Runs Without A Terminal, For A Rehearsal

**Status:** Implementing, since 2026-11-09, on the operator's order to build the rehearsal now; nothing of it reaches
`main` before the operator's move-4 test. Filed 2026-11-09 for the operator's automated rehearsal of a move on a rented
server.
**Size:** M - a walk through every porelo command a move's person runs, unattended, each gap closed with a test.

**What must be true.** Every porelo command a person runs in a move works with no terminal and no person at a keyboard,
given an SSH agent that serves the person's key and a vault opened beforehand by `porelo vault unlock
--passphrase-command` (or a kept passphrase): `identity create`, `org create` and its `--check`, a project's founding
and `project sign`, `vault put` of a forge token from stdin, `task start` of an unattended task, `gate review` and
`approve`, the build reads, and the person's central push and pull. None of them prompts; where one would, it refuses
at once, naming what it lacks (exit 69 or 65 with a reason), never waiting on stdin.

## Why

The operator wants a move rehearsed by the agents on a rented server before their own test. The run is driven by a
script over SSH: a prompt it cannot answer hangs the run until its hard stop, and the failure reads as a timeout, not as
the command that asked. Several commands open a shut vault at a terminal (`openIfShut`), and only some take a
`--passphrase-command`; which of a move's commands still ask has not been walked.

## Plan

- List the commands of a move's person (the move's guide and the acceptance features) and run each without a
  terminal against a VM: stdin closed, no TTY, vault unlocked by a passphrase command, the key in an agent.
- Each that prompts or waits: a refusal naming what it lacks instead, with a test seen red first; each that needs a
  secret takes it on stdin or from the vault, never on a command line.
- One acceptance scenario that runs the person's part of a move from founding to an approved push without a TTY.
