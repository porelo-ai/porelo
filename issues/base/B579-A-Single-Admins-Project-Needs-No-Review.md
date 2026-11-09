# [B579](README.md#b579) — A Single Admin's Project Needs No Review

**Status:** Review. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** S - the default written by project creation, and a word where `required` cannot be met.

**What must be true.** A project created with a single admin is written with `review: optional`; a project whose
`review: required` cannot be met because it has only one admin says so where it is created and in `project show`.

## Plan

Read in the code: `ProjectReviewCommand` writes the review entry; project creation writes `required` whatever the
number of admins.

- Creation: `optional` when the project names one admin; `required` stays where it names more.
- `project show` and the creation's protocol say that `required` waits for a second admin's approval when only one
  exists.
- Red first: a project created with one admin has `review: optional`.

## As built

Both `project create-repository` and `org project add --create-repository` write `review: optional` in the first
`project.yml` when there is at most one unlocked Project Admin and no `--review` was given. Two or more unlocked
admins keep the file's default `required`; an explicit choice is written as given.

On creation, an explicit `required` with fewer than two unlocked admins adds `note` to the creation answer and prints it in text.
It explains the wait for a second admin's approval and names `porelo project review PROJECT optional`, which shows
the entry to edit and commit signed. Porelo does not change an existing project file.

For an organization project, `project show` adds `review_waits: "no second admin"` beside each required review in
`repositories` and its `names` rows when fewer than two unlocked admins exist, and prints the wait in text. Locked
admins cannot supply that approval. Optional reviews and projects known only to the machine carry no warning.
This organization command has no daemon method; the varlink contract is unchanged.

Command tests guard the defaults with one admin, two unlocked admins and a locked second admin, both creation
answers and text notes, and the show warning. Each new guard is seen failing on behavior before the fix; the
two-admin guard rejects a deliberate change to an optional default.

A partial retry preserves the first committed review choice across a change in the unlocked-admin count, while
still requiring the same generated file and signed change id. A completed retry answers its receipt without
inferring a review warning from options that it does not apply; `project show` answers the current review state.
The command tests also guard both retry cases and exercise the creation note in initial JSON and text output.
