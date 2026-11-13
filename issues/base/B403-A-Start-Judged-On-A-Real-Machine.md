# B403 — A Start Judged On A Real Machine

```json
{
  "$schema": "issue-1.json",
  "id": "B403",
  "title": "A Start Judged On A Real Machine",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 32,
  "blockedBy": [],
  "covers": "The acceptance suite walks a start of an organization's project through the judgment on a real machine, signed, unsigned, wrong role and revoked"
}
```

**What must be true.** The acceptance suite starts a task of a project its organization names, on a real machine, and sees
the file's judgment hold: a commit signed by an admin of the project starts, an unsigned one or one by a person without the
role is refused and places nothing, and a key revoked in the organization no longer counts at the next start.

The cut removed the scenarios that promised this of a followed project, along with `project follow`. Unit tests on real git
repositories hold the judgment, but no scenario walks it through the installed package.

## Plan

One new feature in `acceptance/suite`, with an organization in a bare repository on the machine and the kit's own keys: the
four outcomes above, each with the exit code and `CanStart`'s answer. Nothing new in the product. The removed scenarios on
access files, a key handing on to another and a follow's preview have no counterpart any more and are not carried over.
