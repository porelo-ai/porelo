# B782 — A Refused Pull-Request Read Says What It Asked and Got

```json
{
  "$schema": "issue-1.json",
  "id": "B782",
  "title": "A Refused Pull-Request Read Says What It Asked and Got",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 9,
  "blockedBy": [],
  "covers": "A host's pull-request read that the forge answers with an error says the status, the path it asked and a cause chosen by the status, each with what to do, never the token; one repository's error refuses only that repository, whose earlier rows are kept and never ended by the error, and a not-carried one is never asked",
  "openQuestions": 0
}
```

**Why this size.** M: `HostPullRequestReader`'s answer handling and its reasons, which cross varlink in
`HostPullRequestsRefused` and in the read's answer, which the window renders, with their tests. Estimate: 63
minutes (Core's median for M).

**What must be true.** When the review list of a carried project is refused, the person and whoever helps them can
tell from the answer alone whether the token was refused, a repository is not there for the person, or the forge
failed, which repository, and what to do about each; the other repositories' reviews are still answered, and a
review is never reported ended because its repository could not be read.

## Why

`HostPullRequestReader.http` answers every non-2xx status with one sentence, reason `forge`: "the host forge refused
the review request; check access and renew the person token with porelo host forge-token". Neither the status nor
the path asked is said. Seen on a host: right after `porelo host forge-token` stored a fresh token, the read of a
project carrying three repositories was refused with that sentence, and nobody could tell which repository or why.
The forge answers a private repository a login may not read with 404, as it answers one that does not exist, so a
404 alone does not tell a missing copy from a person not admitted.

## Plan

1. `HostPullRequestReader.Exchange.get` hands back the status beside the body; `http` stops deciding. A failed
   connection or timeout stays a whole-read `forge` refusal: it concerns every repository.
2. A non-2xx status is told by a message holding the status (an int the code formats) and the path asked,
   `/api/v1/repos/<copy>/pulls` or `/pulls/<n>`, built from `HostForgeAddress.copyOf`'s checked copy name alone:
   never the host's address, its port, the query, the token, or a word of the forge's body or headers.
   - 401: refuses the whole read, reason `forge-token`: the forge refuses the person token, which every repository
     shares; renew it with `porelo host forge-token`.
   - 403 and 404: refuses that repository, reason `forge-access`: the repository is not there for this person -
     its first copy is not made yet or the person is not admitted; an administrator of the host makes it or admits
     them. The two are not told apart: the forge answers both alike, telling them would say whether a copy exists,
     and nothing a person cannot change records that the host made it (`OrganizationProjects.Placed` holds no such
     mark; the copies are derived from `project.yml`).
   - 5xx and any other status: refuses that repository, reason `forge`: the forge failed; ask again later.
   The same holds for the confirming read of an earlier row (`/pulls/<n>`): its error refuses the repository, never
   ends the row and never stops the round.
3. `Round` gains the refused repositories, each with repository, reason, status, path and message, in the order of
   the carried repositories, and the kept rows: the earlier rows of a refused repository, neither open nor ended in
   the answer. `asMap` carries the refusals inside its size check; at most one per repository (100).
4. `GateMethods.watchPullRequests` keeps the open and the kept rows for the next round, so a review merged while its
   repository was refused is sent ended once when the repository answers again, and never twice.
5. An earlier row of a repository the host no longer carries refuses that repository with `not-carried` and is
   kept, instead of refusing the whole read.
6. `35-gate.varlink`: both methods' replies gain an added `refused` list of a new type, the error's comment lists
   `forge-token` and `forge-access`, and the comment on absence says a refused repository's rows are kept, not
   ended. An added field and reason are not breaking; the window renders an unknown reason by its message
   (Agent Frontend, 2026-11-15) and ignores a field it does not read.
7. `doc/` says what each reason means where the pull-request read is described.

## Red-first test

- `HostPullRequestReaderTest` with a fake exchange: 401 refuses the read with `forge-token`; 403, 404 and 503 each
  refuse their repository with their reason, the status and the path; no message holds the token or `127.0.0.1`.
  Fails today: one reason, no status, no path, the whole read refused.
- One of three carried repositories answers 404: the other two's open reviews are answered, the third named with its
  reason.
- Refused, then merged, then answering: the review is ended exactly once, and not while refused (watch test in the
  daemon).
- An earlier row whose confirming read answers 503: kept, not ended, the repository refused.
- A project with carried and not-carried repositories: only the carried ones are asked; an earlier row of a
  repository no longer carried refuses that repository alone.

## Built

- `HostPullRequestReader`: `Exchange` answers status and body (`Answer`); a non-2xx is judged in `request` with the
  path from the checked copy name alone (`URI.getPath`: no address, port, query or token; no forge words). 401
  refuses the read (`forge-token`); 403/404 refuse the repository (`forge-access`); 5xx refuses it (`forge`, ask
  again later), any other status too (`forge`, check the host's forge settings: a 3xx, since redirects are not
  followed, or a 4xx). The same on the confirming `/pulls/<n>` read.
- `Round` gains `refused` (`Refusal`: repository, reason, status, path, message) in the carried repositories' order,
  those no longer carried after, and `kept`, the earlier rows of refused repositories; `asMap` sends `refused`
  only when it is not empty, inside the size check. Open plus kept rows stay within the 500 a watch carries.
- `GateMethods.watchPullRequests` carries open and kept rows to the next round.
- `35-gate.varlink`: `HostPullRequestRefusal`, `refused: ?[]` on both replies, the added reasons, the kept rows in
  the comment on absence. `doc/hosts.md` says each reason. A changelog entry.
- Red first: 8 of 13 reader tests failed against a stub with the new types and the old judging (the whole read
  refused with one sentence); the daemon's kept-row test failed on the carried rows; the bound and order tests,
  added from the reading, failed against a deliberate break of each, restored from a copy.
- Reading (Sonnet sub-agent, one reading): fixed: kept rows counted in the bound; refusals in a stable order;
  `hasMessageContainingAll` where `hasMessageContaining` checked only its first text (here and in the missing-token
  test); its own words for a non-5xx status. Left (yellow): a review deleted on the forge (its `/pulls/<n>` answers
  404) and the rows of a repository never carried again are kept, and refused, while the watch lasts; the window
  asks again after a refusal ends its stream, which starts with nothing kept. A 401 on the confirming read refuses
  the whole read, untested.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, the read of the project that was refused names which repository and why.
