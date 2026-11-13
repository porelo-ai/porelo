# B719 — The Tools Draft Written

```json
{
  "$schema": "issue-1.json",
  "id": "B719",
  "title": "The Tools Draft Written",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [
    "B220"
  ],
  "covers": "`ToolsDraft` writes the tools draft in the image format, read back through its own reader, into the checkout with a line in `.git/info/exclude`, or for a repository with no checkout under `$XDG_CONFIG_HOME/porelo/projects/default/<repository>/.porelo.yml`, which the image order then reads first for a REMOTE entry.",
  "openQuestions": 0
}
```

**Why this size.** One writer, its read-back, one more source in [B192](README.md#b192)'s order and their tests.

Split from PJ52 on 2026-11-13. [B221](README.md#b221) left out steps 4 and 6: the writer of `.porelo.yml` and the place for a repository with no checkout. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `ToolsDraft` written in the image format and read back through the image reader before it is kept.
- In a checkout: the file written and a line added to `.git/info/exclude`; tracked files stay unchanged.
- For a REMOTE entry: written under `$XDG_CONFIG_HOME/porelo/projects/default/<repository>/`, and [B192](README.md#b192)'s order reads it first.

## Acceptance

- Red first: a written draft read back gives the same tools; a checkout's tracked files are unchanged.
- A REMOTE entry's image order picks the draft under the configuration directory.
