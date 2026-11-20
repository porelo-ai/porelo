# B816 — Makers and Copies of a Carried Project Before Its First Start

```json
{ "$schema": "issue-1.json", "id": "B816", "title": "Makers and Copies of a Carried Project Before Its First Start",
  "state": "Idea", "size": "M", "priority": "High", "rank": 139, "blockedBy": [],
  "covers": "A carried project's open pull requests answer before its first start with their makers, read from the person's gate mirror brought up as a first start would, from the copy each repository's central address names; the window shows no cannot-be-read item for it" }
```

**Why this size.** M: `HostPullRequestReader` brings a missing mirror up and maps repository keys to forge copies
from what the host publishes, with tests for each and the carried path's refusals.

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

- To be analyzed: whether the read brings the gate's mirror of each carried repository up as a first start does
  (from its central address, with the person's token, on loopback), or fetches only the head commits into it; and
  where the host publishes each repository's central address, so the copy is named as the file path names it.
