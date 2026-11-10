# B404 — A List With The Organization Never Read

```json
{
  "$schema": "issue-1.json",
  "id": "B404",
  "title": "A List With The Organization Never Read",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 33,
  "blockedBy": [],
  "covers": "`project list` still shows a machine's own projects when its organization was never read and is out of reach"
}
```

**What must be true.** `porelo project list` shows this machine's own projects and `default` when its organization was
never read and is out of reach, saying that the organization could not be read, since a start of such a project goes. Today
the whole list answers 69 `cannot-say`.

Seen on 2026-11-03 on a machine with `organization.yml` naming an address that refuses connections.

## Plan

`ProjectListCommand` catches `cannot-say` from the read and answers the local rows with `revision` `null` and `stale`
`true`; every other refusal stays. Test in `OrgReadingCommandsTest`, and the sentence in `doc/commands.md` goes.
