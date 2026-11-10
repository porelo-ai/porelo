# B415 — A Project Admin Told How To Open Without The Right

```json
{
  "$schema": "issue-1.json",
  "id": "B415",
  "title": "A Project Admin Told How To Open Without The Right",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 22,
  "blockedBy": [],
  "covers": "`project open` refused by the forge says what works: somebody makes the repository under the project's name, empty, with write for this person, and they ask again"
}
```

**Severity.** low: nothing is lost and asking again is safe, but the person is left without a way on.

**What must be true.** A project admin whose token may not make a repository on the forge is told what does work: somebody with
the right makes the repository under the project's name, beside the organization's, empty, with write for this person, and they
ask again.

**What holds today.** Seen in a walk at GitHub: `project open` with a token without Administration answered only "the forge does
not let your token make a repository there". Read in the code, not run:

- `ProjectOpenCommand` asks the address over ssh first and calls the forge only when it is not there, so an empty repository made
  by somebody else is filled and filed, with no right on the token. Nothing says so, neither the refusal nor `doc/commands.md`.
- `ForgeRepositoryMaker.refused` answers the same words for 401 and 403, and "the forge holds a repository of that name that you
  cannot read" for 409 and 422. A person not yet given access to a repository made for them gets one of these, and the second
  does not say that they need write.

**To be checked.** Which status GitHub answers for a repository that exists and that this person cannot read, measured with a
token that may and one that may not make repositories. Whether the refusal names the address, which is known at that point.
