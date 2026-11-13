# B669 — A Project Names Where Its Readings Go

```json
{
  "$schema": "issue-1.json",
  "id": "B669",
  "title": "A Project Names Where Its Readings Go",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 22,
  "blockedBy": [],
  "covers": "project.yml takes an optional readings.directory, relative to the repository root without '..' or a leading '/', default 'readings'; a bad one is refused when the file is read, and a repository's porelo.yml that names one is refused by its name.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST313, part 2, from [PJ109](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj109) as the operator decided it: `readings:
{directory: readings}` in `project.yml`, default `readings`. Analyzed by Agent Core. On 2026-11-13 the operator decided
that no repository overrides it: the key lives only in the signed `project.yml`.

**What must be true.** A project names the directory where a reading of an issue writes its findings file
(`<dir>/<ISSUE>-reading-<round>.md`). Only the project names it; a repository's `porelo.yml` may not. The directory
stays inside the repository.

**Size.** S: one optional key, its check, the model field, the refusal in `porelo.yml`, and the docs.

## Plan

Read in the code on 2026-11-12: neither `project.yml` nor `porelo.yml` has a `readings` key.
1. **Red first.** `ProjectReaderTest`: `readings.directory: ../x` and `/x` are refused, naming the key. A good value
   is read, and the default is `readings`. A `porelo.yml` naming `readings` is refused by its name.
2. **The key.** Optional top-level `readings: {directory: <path>}`. Its path is relative to the repository root, with
   no `..` segment and no leading `/`; an empty one is refused. A repository's `porelo.yml` may not name it, as it
   may not name `repositories` or `mcp`.
3. **Where it goes.** The task is told its directory as it is told its other settings (`doc/running.md`, "Values a
   task is started with"), so the agent and the planner read the same value.
4. **Docs.** `doc/project-file.md` shows the key once, with its default.

## Acceptance

- `readings.directory: ../x` is refused when the file is read. Seen red first.
- A repository's `porelo.yml` naming `readings` is refused by its name; every repository gets the project's
  directory or the default.

## Done

- `project.yml` takes an optional top-level `readings: {directory: <path>}`. Without it the directory is `readings`.
  A repository's `porelo.yml` that names `readings` is refused by its name at the start, as `repositories` and `mcp`
  are (`RepositorySettings`); the operator decided on 2026-11-13 that only the signed `project.yml` names it.
- The file check refuses an empty directory, a leading `/`, a `..` segment and a control character, naming
  `readings.directory`, when the file is read.
- A task is told its directory in `PORELO_READINGS_DIRECTORY`, beside its other settings. The key is described with
  the project's other keys, and `doc/project-file.md` shows it once with its default.
- Red first: `ReadingsDirectoryTest` (11) failed 7 without the check, and the two `TaskRunCommandTest` cases failed
  before the task was told. Locally on this commit `-pl apps/app -am install` (4618 in app), the root's `validate`
  and `-Pdocuments test` are green.
- The repository's override, first built and then taken out on the operator's decision: the test that a
  repository's directory wins became `ReadingsDirectoryTest.aRepositorysPoreloYmlNamingReadingsIsRefusedByItsName`,
  which failed while the key was taken, and the test of a repository's directory outside it went with the override.
- Locally on the shrink: core (588) and apps/app (4673) passed whole, one at a time, and so did the root's `validate`
  and `-Pdocuments test`.
