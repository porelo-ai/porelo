# B147 — A Wall-Clock Limit For Unattended Runs

**Status:** open.

**What must be true.** An unattended run ends at a time limit its project or its start sets, and says so.

## Why

An agent left to itself can run for as long as nobody looks.

**Guideline points it answers:** AISVS 9.1.1. From a review of security guidelines, 2026-10-09.

## The shape

A limit per project and per start; the run stopped as `task stop` stops it, its work kept; the reason in the task's status.


## A likely fault in today's limit, unmeasured

`task start --minutes` (default 30) ends an unattended run by killing the host's `podman exec` client at the deadline
(`Podman.execute`, `destroyForcibly`). Killing an exec client does not end the process it started in the container, and
without `--rm` - the default - nothing stops the task afterwards. So the agent most likely goes on working past its limit,
unseen, with its gate, proxy and firewall up. To be measured on a test machine first: after the limit, is the agent's
process still alive in the container?

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether a task with a person attached is exempt.
