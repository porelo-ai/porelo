# B465 — An Older Project File Is Refused By Ancestry, Not By Date

```json
{
  "$schema": "issue-1.json",
  "id": "B465",
  "title": "An Older Project File Is Refused By Ancestry, Not By Date",
  "state": "Idea",
  "size": "S",
  "priority": "Low",
  "rank": 13,
  "blockedBy": [],
  "covers": "A machine remembers the project file's commit it accepted and refuses a later one that does not descend from it, so a file signed in the same second as a newer one cannot come back."
}
```

**Why this size.** what a machine remembers of the file it accepted, and how it compares a later one, with its tests.

**Severity.** medium: anyone with write access to a project's repository can bring back an older file an admin signed, in a
narrow case.

**What must be true.** Once a machine accepted a project's file, it never accepts a file signed before that one, even if both
carry the same commit date.

## Found

Found by a reading by another model of the commit that signs a file anew, on 2026-11-06, and settled by no part of that fix.

`ProjectFile.judged` remembers only the date of the newest change it accepted and refuses a change dated before it
(`dated.isBefore(accepted)`). A commit's date has second precision, and the signer writes it. When two versions signed by an
admin share a second, a writer with no admin key can make an unsigned merge whose first parent and tree restore the older one
after a machine accepted the newer. The older signature then carries the remembered date, is not before it, and is
accepted. An older commit that signs the file anew can come back the same way. Read in the code; not run.

## What it asks

A machine remembers the commit it accepted, beside its date. A later read accepts only a newest change that has that commit as
an ancestor, or is that commit. A file that went back is refused as older, as today, and an admin gets out of it with
`porelo project sign`. A record from before this, with a date only, keeps today's comparison until the next file is accepted.

**Red first:** in `ProjectFileTest`, two admin-signed versions with the same date, the newer accepted, then the older brought
back by an unsigned merge: refused. The same with an older commit that signs the file anew.
