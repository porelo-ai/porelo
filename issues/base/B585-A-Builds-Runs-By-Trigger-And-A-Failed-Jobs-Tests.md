# [B585](README.md#b585) — A Build's Runs By Trigger, And A Failed Job's Tests

**Status:** Review. Filed 2026-11-09 under ST290, from the fourth move, on Agent Adapters' proposal.
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

## As built

- Tasks1's build runs carry optional `event` and `id`; jobs carry optional `run`, `failedTests` and `url` through
  delivery, the durable record, resume and the daemon-facing map. Old records without those fields retain their
  earlier shape. Metadata-only job updates replace the stored answer even when the verdict is unchanged.
- `BuildFields` in `wire` holds the existing build API's bounds and checks; `Build` and `TaskBuilds` use that one
  implementation. The module dependencies and POMs are unchanged.
- Build status lists runs by trigger and id, with their jobs indented below. The API clears an unknown run reference
  and keeps the job; status lists that job apart. Ambiguous duplicate ids also leave the job apart, shown once.
  A failed job's named tests replace its log in both human and JSON output; JSON keeps the log field empty.
  A page is retained only on the selected repository upstream's host, ignoring case, regardless of the API host.
  Token redaction covers lowercased metadata and prefixes left at a field's bound.
- Behavior tests were seen red for adapter delivery, stored-field bounds, grouped status, unknown runs, failed
  tests replacing logs, JSON metadata and credential redaction. Deliberate regressions also made the legacy-read,
  foreign-page, optional-contract, duplicate-id and resumed metadata-update tests fail; the saved implementations
  were restored. Every added test has a one-line harm comment.
- The focused offline Maven run, after cleaning the changed Java modules, passed 113 test invocations. Eighteen
  existing socket-based interface-test invocations errored before binding: the socket ownership guard sees an
  unmapped ancestor owned by uid 65534 instead of the process's uid or root. The new optional build-contract test
  passed. Machine acceptance, native packaging and a full suite were not run in this worktree-only task.
- Read-only review used Graal 2026.910.3 and the six Java review skills at 2026.906.3. Its resume-test setup,
  redundant normalization and transformed-token findings are addressed with behavior tests. A separate API
  transport-budget concern is deferred beyond this adapter-and-status change: the existing allowed logs plus
  maximum multibyte failed-test names can exceed varlink's 4 MiB message limit. This is a bound calculation,
  not an executed transport test.
