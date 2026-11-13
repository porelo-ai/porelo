# B410 — Two Forges On One Host Told Apart

```json
{
  "$schema": "issue-1.json",
  "id": "B410",
  "title": "Two Forges On One Host Told Apart",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 27,
  "blockedBy": [],
  "covers": "An address on another port of a host is not taken for the same repository, and a person is told which forge their work goes to"
}
```

**Severity.** low: it takes two forges on one host holding the same path, and what goes wrong stays on that host.

**What must be true.** Work named for a forge on one port of a host goes to that forge, not to another on the same host whose
repository has the same path, and a person who gives such an address is told which forge their work goes to. The same forge
reached by ssh on its own port and by https stays one repository.

**What holds today, measured.** `RepositoryAddress.same` compares host and path, without the port, so it answers true for
`https://forge.example:8443/a/b.git` against `https://forge.example/a/b.git` and against `https://forge.example:9443/a/b.git`.
It answers true for `ssh://git@forge.example:2222/a/b.git` against `https://forge.example/a/b.git` too, and that one is
wanted. Read in the code, not run:

- `DefaultProject.add`, through `withUpstream`, answers the entry already there, so a second forge is never added and its work
  goes to the first.
- `WorkOrigin.placedNaming` takes such a checkout for the placed project, and the sentence says its work goes to the checkout's
  origin, while it goes to the upstream the project names. The same holds for `https://forge.example#@other.example/a/b`
  against `https://other.example/a/b`, where `same` also answers true.

**A token, the same way.** The entry `git.token.<host>` is found for every port of its host, so a fetch of another port is lent
it too. `doc/credentials.md` says a token is lent to the scheme, host and port and that another port of that host does not get
it, which holds only for one lease. Decide whether that stays, and say it plainly.
