# B800 — A Repository Without Review Takes The Project's

```json
{
  "$schema": "issue-1.json",
  "id": "B800",
  "title": "A Repository Without Review Takes The Project's",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 77,
  "blockedBy": [],
  "covers": "A repository entry without its own review takes the project's review; required holds only when both are absent, and a file naming review per repository reads as before"
}
```

**Why this size.** S: one default in `ProjectReader` (`core`), its unit tests and `doc/project-file.md`; every other
reader takes the value the reader resolved. It decides who may merge, so its plan is read by another model before it
is built.

**What must be true.** A project admin who writes `review: "optional"` once for the project gets it for every
repository that says nothing of its own; `required` holds only where neither the repository nor the project names a
review. A file that already names `review` on a repository reads as before.

## Found

`ProjectReader.repositories` builds each `Repository` with `review(entry.review())`, and `review` answers
`REVIEW_REQUIRED` for `null`, whatever the project's own `review` says. A project with `review: "optional"` and no
review on its repositories reads every repository as `required`, so `project show` warned once per repository and the
host's forge protected each one as needing a second admin.

## Plan

- `core/.../project/ProjectReader.java`: `repositories(...)` takes the project's resolved review (`review(info.review())`,
  line 280) and builds each repository with `entry.review() == null ? projectReview : entry.review()`; `review(null)`
  stays `required` for the project itself.
- Readers of a repository's review, all fed by that value, checked unchanged:
  - `ProjectVerifyCommand` answers `review` per repository and `project_review`; the host's `FollowProjects.values`
    and its branch protection (`protect`) take them as given - this is the path that decides who may merge on the
    host's forge.
  - `FollowProjects.protect` (around line 1397) takes each listed repository's `review` from that verified answer,
    so it follows the fix unchanged; a repository on the forge that the file does not list still falls back to
    `required` there, which stays.
  - `ProjectShowCommand` (`repositories.names[].review` and `review_waits`) shows the resolved value.
  - `ProjectCreateRepositoryCommand` writes only the project's own `review` into a new file; unchanged.
  - The gate's local review (`GitGate.review`) does not read the setting.
- `doc/project-file.md`: the project's `review` says it is also every repository's default; the repository's
  `review` says it overrides the project's and that, without either, `required` holds.
- Changelog entry under *Changed*: a repository without its own `review` follows the project's.
- Unit tests, red first: in `ProjectReaderTest`, a repository without `review` under a project with `optional` reads
  `optional`, and under a project with `required` (and with none) reads `required`; a repository naming `required`
  under an `optional` project stays `required`; and in `ProjectVerifyCommand`'s test, the answer's per-repository
  `review` is the resolved one, since that is what the host's branch protection sets. The harm guarded: a merge
  allowed without approval, or refused for one that needs none.

## Acceptance

- The `ProjectReader` cases and the `ProjectVerifyCommand` case pass, each seen red on today's code.
- `porelo project verify` and `porelo project show --json` on a file with `review: "optional"` and repositories
  without `review` answer `optional` for each repository.
- A project file whose repositories each name `review` answers the same as before.
