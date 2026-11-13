# B710 — No Agent Waits For A Build That Never Comes

```json
{
  "$schema": "issue-1.json",
  "id": "B710",
  "title": "No Agent Waits For A Build That Never Comes",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 64,
  "blockedBy": [],
  "covers": "An agent whose push will not be built while its task runs, because the work waits at the gate for a person or the project follows no builds, is told so at once and does not wait for a build report.",
  "openQuestions": 0
}
```

**Why this size.** A hand-in at the push in `BuildWatch` or the gate, one guide paragraph, their tests, and the
interface story's walk again.

Filed 2026-11-13 from Frontend's walk of an interface story on the test VM, with Core 40b17b120. A builder task
pushed its work within 13 seconds, and the gate took it. The agent then waited 30 minutes for a build report and said
"A build workflow exists, so a report should arrive", while Porelo listed `builds: []` for it. Its time limit ended it
unfinished, and the story's plan failed on it.

The guidance asked for that. `TaskGuideText` tells every task, the same for all: **"After you push, read
`build-<commit>.txt` until its verdict is final, before you call the work done."** The paragraph above it says this
holds only "where the project follows its builds", but nothing tells the agent whether its project does. Nothing tells
it either that a push held at the gate for a person is not built until somebody passes it on. An agent that finds a
build workflow in the repository can only wait. The wait is the agent's choice, but our text led it there.

**What must be true.** For every push, the agent can tell from what Porelo hands in that no build comes while the
task runs. The guide says that it may then call the work done.

## Plan

- At a push that will not be built while the task runs (held at the gate for a person, or a project that follows no
  builds), hand in `build-<commit>.txt` at once with `verdict: unknown` and a `detail` that says why, for example
  "held at the gate for a person; nothing is built until it is passed on".
- The guide: `unknown` is final. With it, the work is done once it is pushed. The shared text stays the same for
  every task, so it says this as a rule and does not depend on the project.
- If a held push is passed on later and built, the file is replaced as before.

## Acceptance

- A unit test: a push held at the gate hands in `unknown` with its reason, at once.
- A unit test: the guide names `unknown` as final, and a push with nothing to build as done.
- The interface story's walk with a builder task ends finished.
