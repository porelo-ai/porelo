# [B585](README.md#b585) — A Build's Runs By Trigger, And A Failed Job's Tests

**Status:** Implementing. Filed 2026-11-09 under ST290, from the fourth move, on Agent Adapters' proposal.
**Size:** M - four optional fields in Build2 and its Java types, carried through Tasks1's build answer, and the status
that prints them.

**What must be true.**
- Build2's `Run` gains `event` (the forge's trigger, lower case: `push`, `pull_request`, ...) and `id` (the forge's
  run id as text); `Job` gains `run` (the id of the run it belongs to), `failedTests` (for a failed job, the tests that
  failed as the runner names them, at most 50) and `url` (the forge's page of the job). All optional: a reader or
  caller from before still works.
- `porelo build status` lists one line per run, push or pull request named, with its jobs under it; a failed job prints
  its failed tests and its page, and the end of its log only when it names no failed tests.

## Plan

- `builds/api`: `org.fuin.porelo.Build2.varlink` and `Build.Run`/`Build.Job` with the fields, as text Porelo makes one
  line: `event` and `id` at most 64 code points, `failedTests` at most 50 lines of at most 200, `url` kept only as an
  `https` URL of at most 2048 characters without spaces. A job's `run` that names no run of the build is dropped.
  `asMap`/reading carry them; old readers' answers without them read as before.
- porelo: `TaskBuilds` and Tasks1's build answer carry them; `build status` prints runs with their jobs, and a job's
  page only where its host is the build's forge.
- Red first in `BuildTest`: the fields survive a round trip; a forged multi-line test name becomes one line; a job's
  `run` naming no run is dropped; a non-https url is dropped. In the status test: a failed job with failed tests
  prints them and its page, not its log.
