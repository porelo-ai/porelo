# B265 — A Reserved Variable Refused With Its Fix

**Status:** Backlog. Built together with [B268](README.md#b268) and [B267](README.md#b267).
**Size:** S - one refusal and the one place Porelo's own values are named, in `core` and `runtime`, with their tests.

**What must be true.** When a repository's `porelo.yml` sets a variable Porelo sets in every task (`PATH`, `HOME`,
`LANG` or any `PORELO_*`), the refusal says what Porelo sets it to and what to do: remove the line.

## Why

Seen in the operator's test on a rented host on 2026-10-23: a task start in `porelo-message-matrix` was refused with
"porelo.yml at refs/heads/main of matrix: 'image.env.LANG' is set by Porelo and cannot be set here". The refusal was
right. Porelo writes `ENV LANG=C.UTF-8` into every task image, exactly the value the line set. But the words name no
value and no fix, so the person reading it, often not the repository's owner, could not tell that removing the line
changes nothing.

Read in the code: `RepositoryImageReader` refuses every name in `PORELO_OWNS` and every `PORELO_*` with the same
sentence.

## Plan

- **One place names what Porelo sets**: a `TaskVariables` class in `core` (`org.fuin.porelo.core.project`) holds
  `PATH` (`/home/agent/.local/bin:/usr/local/bin:/usr/bin:/bin`), `LANG` (`C.UTF-8`), `HOME` (`/home/agent`, the
  agent user's home) and the rule that every `PORELO_*` is set per task.
  - `runtime`'s `Containerfile` writes its `ENV PATH` and `ENV LANG` lines from it, since `runtime` depends on `core`.
  - `RepositoryImageReader.PORELO_OWNS` and the copy of the list in `Parameter`'s `env` check read it, so the three
    lists become one.
- **The refusal** in `RepositoryImageReader.env` names the value and the fix:
  `porelo.yml at refs/heads/main of matrix: 'image.env.LANG' is set by Porelo to C.UTF-8 in every task; the file is
  the repository's, so whoever commits there removes the line - the task has that value without it`. A `PORELO_*` name
  says Porelo sets it per task, and that a value the repository needs goes under another name.
- **Every refusal of a repository's `porelo.yml` says that nothing was created**: today the refusal before the fetch
  (`TaskLaunch`, the preview) ends 64 without that line, the one after the fetch with it. Both end the same way.
- **Test red first:** `RepositoryImageReaderTest` asserts that the refusal of `LANG` contains `C.UTF-8` and
  `remove the line`, and that the `ENV` lines `Containerfile` writes carry the same values `TaskVariables` names.
  The second holds the one place: a value changed in `Containerfile` alone fails it.
- A changelog line under *Changed*.
- **Build estimate=45**, like porelo [B259](README.md#b259) (S, one refusal and its test), a little less.

## Acceptance

- A `porelo.yml` setting `LANG`, `PATH`, `HOME` or a `PORELO_*` name is refused with Porelo's value, or with "set per
  task" for `PORELO_*`, and the words "remove the line".
- The values in the refusal and in the image's `ENV` lines come from one class; a test fails when they part.
