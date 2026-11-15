# B788 — The Review Read Asks Only What the Host Carries

```json
{
  "$schema": "issue-1.json",
  "id": "B788",
  "title": "The Review Read Asks Only What the Host Carries",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 5,
  "blockedBy": [],
  "covers": "The host's record of a project names the repositories its card carries, and the review read judges which are carried as the project's host status does, so a repository the window shows as not carried here is never asked for and never refused, and a host status that does not read refuses the read instead of answering no reviews",
  "openQuestions": 0
}
```

**Why this size.** M: the host's follow writes the card's repositories into the record it publishes, and the review
read takes the judgment the inventory already uses, each with a guard test. Estimate: 63 minutes (Core's median for
M).

**What must be true.** The repositories a review read asks the host forge for are exactly those the window shows as
carried here; a repository not carried is neither asked nor named as refused.

Filed 2026-11-15 from the operator's live test on a rented host (porelo 0.5.1~snapshot.552), as a bug.

## Why

On that host project `porelo` carries 3 of its 19 repositories (claude, omp, pi); the window shows the other 16 as
"Not carried here". Yet "Needs you" held 16 refused rows, one per repository not carried, each "The forge answered
HTTP 404 when asked for /api/v1/repos/porelo/porelo-<name>/pulls ...". The read takes the carried set from the
project's host record (`OrganizationProjects.host(...).repositories()`), where an empty list means the whole project;
the window takes it from the host's published status (`HostCentral.Status`, its `host_repositories`, read through
`ProjectInventory.hostStatus` and `Status.permitsRepository`). On this host the record names none while the status
names 3 taken, so the read asked all 19 and, as a repository's error now refuses that repository alone, named each
missing copy instead of failing the whole read.

## Plan

0. `FollowProjects.publication` (porelo's host program, `org follow`) writes the card's `repositories` into the
   project's record when the card names some (absent, null or empty carries all, and the record then names none, as
   `OrganizationProjects.Placed` reads it). Today the record never names them (Agent Machines, 2026-11-15: the card's
   list is read at `FollowProjects` but not copied), so every reader of the record - the start's clone addresses in
   `HostForgeAddress`, the inventory's `carriedHere`, `org` listings, the review read - takes such a project as
   carried whole.
1. `HostPullRequestReader.repositories` asks `HostCentral.status(project)`, the same checked file the inventory
   reads, and keeps a repository only where the window would show it carried: the project's own by
   `permitsCarrying()`, every other by `permitsRepository(name)`. The record's list stays a second bound: a
   repository it names as not carried is never asked either.
2. A status that does not read (`cannot-say`) refuses the whole read with `not-carried` and words that the host's
   carry state does not read, rather than asking nothing and answering an empty list.

## Red-first test

- `FollowProjects` / `HostFollow` test: a card that names 3 of a project's repositories publishes a record whose
  `repositories` are those 3; a card naming none publishes none. Fails today: none in either.
- `HostPullRequestReaderTest`: a project of 19 repositories whose host record names none and whose host status
  names 3 taken: exactly those 3 are asked, and the round holds no refusal. Fails today: 19 asked, 16 refused.
- A host status that does not read: the read is refused with `not-carried`. Fails today: every repository asked.

## Acceptance

- The red-first tests pass, each seen red first.
- On the host, "Needs you" holds no refused row for a repository not carried here.
