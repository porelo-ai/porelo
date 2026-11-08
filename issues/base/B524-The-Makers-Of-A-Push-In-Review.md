# [B524](README.md#b524) — The Makers Of A Push In Review

**Status:** Backlog. Filed 2026-11-08 for Agent Frontend's F440 (move 3's finding 14).
**Size:** S - two Tasks1 fields filled from git.

**What must be true.** `PendingPush` carries its head commit's `author` and `committer` (`name <email>`) and
`trailers` (the message's trailer lines, as `git interpret-trailers --parse` reads them). `Review` carries a
`commits` list beside `log`, each with `commit`, `subject`, `author`, `committer` and `trailers`. The window shows
them and checks one Assisted-by and no Co-Authored-By itself; the daemon judges nothing.

## Why

`PendingPush` holds `commit` and `subject` only, and `Review`'s log is `%H %s`, so the window says "not reported by the
machine" where the reviewer needs to see who made a commit and with what.

## Acceptance

- Red first: a pending push of a commit with two trailers reports both, and its author and committer.
- Names and emails pass through as git gives them, control characters shown as the gate shows a subject.
