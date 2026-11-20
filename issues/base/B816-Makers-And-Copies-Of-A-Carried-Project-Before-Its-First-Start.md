# B816 — Makers and Copies of a Carried Project Before Its First Start

```json
{ "$schema": "issue-1.json", "id": "B816", "title": "Makers and Copies of a Carried Project Before Its First Start",
  "state": "Implementing", "size": "M", "priority": "High", "rank": 139, "blockedBy": [],
  "covers": "A carried project's open pull requests answer before its first start with their makers, read from the person's gate mirror brought up as a first start would, from the copy each repository's central address names; the window shows no cannot-be-read item for it" }
```

**Why this size.** M: two modules (`app-model` for the read, `daemon` for taking the project first), one contract
sentence, and tests over a local git server for the fetch; no other repository changes.

**What must be true.** On a rented host, a person whose project the host carries sees that project's open pull
requests, with their makers, before starting any work there, and the window shows no "cannot be read" item for it.

## Found

Left by [B796](README.md#b796), read in its code and its reading:

- **Makers.** Each open pull request's author, committer and trailers are read from the person's local gate mirror
  (`HostPullRequestReader.makers`; the contract says never from the forge's user fields). Before the first start
  that mirror does not exist, so a carried project with an open pull request is refused with `makers`.
- **Copies.** The carried path asks the copy `<project>/<repository key>`; the file path takes the last segment of
  the repository's central address (`HostForgeAddress.copyOf`). Where they differ, the carried path asks the wrong
  copy. The host's record names repository keys only.
- **Small, from the reading:** no test of the carried path for a withheld or `cannot-say` carry state, an empty record
  list while the carry state names no repositories, a record naming an invalid repository name, a malformed host
  record or person list, or that every asked address is on loopback; the other-person test proves the person list,
  not the record file's permissions.

## Plan

Read in porelo at aec066e43.

- **Copies: take the project first, as a question before a start does** (decided: a pull-request read places the
  carried project as a first start would, and keeps fetched review heads in Porelo's own store). `ProjectTaking.takeToAsk(context, name)`
  (`app-task`) already places a carried project once, when none stands here yet: it fetches the project repository
  from the host's forge with a token the host's helper makes for this person (`HostForgeAccess`, loopback only),
  checks its signatures and writes the placed project. `CanStart` calls it for the same reason. `PullRequests` and
  `WatchPullRequests` call it once before the first read; afterwards `ProjectSource.resolve` finds the verified
  `project.yml`, and the file path names each copy from the repository's central address
  (`HostForgeAddress.copyOf`), as for a started project. The host-record path [B796](README.md#b796) added in
  `HostPullRequestReader.poll(String, ...)` is then left only for a take that is refused, which keeps today's
  refusal; its own copy naming (`<project>/<key>`) goes, so a wrong copy is never asked. A watch takes once, never per
  round.
- **Makers: fetch the head into Porelo's own store when the mirror lacks it.** `makers(mirror, commit)` reads from the
  gate's mirror only. Where the mirror does not exist or lacks the head, the reader fetches exactly
  `refs/pull/<number>/head` from the repository's copy on the host's forge (loopback, the person's token in git's
  environment as `HostForgeAccess` passes it, redirects off, a size and time bound) into a bare object store of its
  own, `$XDG_STATE_HOME/porelo/reviews/<project>/<repository>.git`. It checks that the fetched ref names exactly the
  row's `head`, then reads the makers there as today. The gate's mirror is never written. `TreeRepository` already
  fetches `refs/pull/<n>/head` the same way for card proposals.
- **Contract.** `35-gate.varlink`, `HostPullRequest`: makers are read from the local mirror or from the head fetched
  from the host's forge into Porelo's own store, never from the forge's user fields. `doc/hosts.md` says the same.
- **Left by [B796](README.md#b796)'s reading, done here:** tests of the carried path for a withheld or `cannot-say` carry state, a
  malformed host record or person list, and that every asked address is on loopback.
- **Red first:** a carried, unplaced project with one open pull request answers it with its makers (today
  `makers`); a repository whose key differs from its central address's name is asked under the copy the address
  names (today the key); the fetched ref not naming the row's head is refused `makers`; the mirror stays untouched.

## Acceptance

- On a host that carries a project no start has placed, `WatchPullRequests` answers its open pull requests with
  their makers, and the window shows no "cannot be read" item for it.
- A head the forge answers differently from the row is refused, and nothing is written into the gate's mirror.
