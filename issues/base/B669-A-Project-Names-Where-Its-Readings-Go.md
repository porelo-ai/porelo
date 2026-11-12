# B669 — A Project Names Where Its Readings Go

```json
{
  "$schema": "issue-1.json",
  "id": "B669",
  "title": "A Project Names Where Its Readings Go",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 22,
  "blockedBy": [],
  "covers": "project.yml takes an optional readings.directory, a repository's porelo.yml may give its own, relative to the repository root without '..' or a leading '/', default 'readings'; a bad one is refused when the file is read.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST313, part 2, from [PJ109](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj109) as the operator decided it: `readings:
{directory: readings}` in `project.yml`, overridable in `porelo.yml`, default `readings`. Analyzed by Agent Core.

**What must be true.** A project names the directory where a reading of an issue writes its findings file
(`<dir>/<ISSUE>-reading-<round>.md`). A repository may name its own. The directory stays inside the repository.

**Size.** S: one optional key in two files, its check, the model field, and the docs.

## Plan

Read in the code on 2026-11-12: neither `project.yml` nor `porelo.yml` has a `readings` key.
1. **Red first.** `ProjectReaderTest` and the `porelo.yml` reader's test: `readings.directory: ../x` and `/x` are
   refused, naming the key. A good value is read, and the default is `readings`.
2. **The key.** Optional top-level `readings: {directory: <path>}`. Its path is relative to the repository root, with
   no `..` segment and no leading `/`; an empty one is refused. `porelo.yml`'s `readings.directory` wins for its
   repository. The model gives the directory for a repository.
3. **Where it goes.** The task is told its directory as it is told its other settings (`doc/running.md`, "Values a
   task is started with"), so the agent and the planner read the same value.
4. **Docs.** `doc/project-file.md` shows the key once, with its default.

## Acceptance

- `readings.directory: ../x` is refused when the file is read. Seen red first.
- A repository whose `porelo.yml` names its own directory gets that one, and every other repository gets the project's
  or the default.
