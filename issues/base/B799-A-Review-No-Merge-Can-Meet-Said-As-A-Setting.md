# B799 — A Review No Merge Can Meet Said As A Setting

```json
{
  "$schema": "issue-1.json",
  "id": "B799",
  "title": "A Review No Merge Can Meet Said As A Setting",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 76,
  "blockedBy": [],
  "covers": "project show says required review without a second admin as a setting no merge can meet, once for the project or one line naming the repositories, with the command that changes it; not as a merge that waits"
}
```

**Why this size.** XS: the text form of `review_waits` in `ProjectShowCommand.text` (`apps/app-organization`), the
one assertion of `OrgReadingCommandsTest` that holds the old words, and the sentence in `doc/commands.md`; the JSON
field and its value stay, so no contract changes. [B800](README.md#b800) lets repositories without their own
`review` follow the project's, so a project set `optional` shows no warning at all; this issue stays for a project
that is `required` and has one admin, where the line still misleads.

**What must be true.** A person reading `porelo project show` takes the review warning for what it is: the project's
setting `review: required` cannot be met while the project has fewer than two unlocked admins, so no merge in those
repositories could ever be approved. It never reads as a concrete merge waiting somewhere, and it says how to change
the setting.

## Found

For every repository with `review: required` while the project has fewer than two unlocked admins, the text prints

    Review (core): a merge waits for a second admin's approval (no second admin)

which reads as a pending merge; there was none. On a project with nineteen repositories the line repeats nineteen
times.

## Plan

- `ProjectShowCommand.text` gathers the rows carrying `review_waits` instead of printing one line per row in
  `reviewWait(String, Map)`:
  - when the project's own `repositories.review_waits` is set and every repository row carries it, one line for the
    whole project, for example `Review: required, but the project has no second admin, so no merge can be approved;
    run 'porelo project review <project> optional' to change it`;
  - otherwise one line naming the repositories that carry it, with the same consequence and command.
- The command is the one `ProjectCreateRepositoryCommand.note` already names (`porelo project review <project>
  optional`).
- `--json` is unchanged: `review_waits: "no second admin"` stays on the project and on each required row.
- `OrgReadingCommandsTest` (around line 1167) asserts the old words `second admin's approval` on the text; that
  assertion holds a wording, so it is reduced to what can do harm - the text still names the affected repository -
  and the words are not held.
- `doc/commands.md`, the `project show` row, says the text names the setting and its consequence, not a waiting
  merge.
- No new test: wording only, per the test rules.

## Acceptance

- `porelo project show <project>` on a project with one unlocked admin and all repositories `required` prints one
  review line for the project, naming the setting, that no merge can be approved, and the `project review` command.
- With only some repositories `required`, one line names exactly those.
- `--json` answers the same `review_waits` fields as before.
