# [B579](README.md#b579) — A Single Admin's Project Needs No Review

**Status:** Implementing. Filed 2026-11-09 under ST290, from the fourth move.
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
