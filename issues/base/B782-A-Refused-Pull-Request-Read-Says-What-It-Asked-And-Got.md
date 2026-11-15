# B782 — A Refused Pull-Request Read Says What It Asked and Got

```json
{
  "$schema": "issue-1.json",
  "id": "B782",
  "title": "A Refused Pull-Request Read Says What It Asked and Got",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 9,
  "blockedBy": [],
  "covers": "A host's pull-request read that the forge answers with an error says the status, the path it asked and a cause chosen by the status, each with what to do, never the token; one repository's error refuses only that repository, a not-carried one is never asked, and a copy not made yet waits without a refusal",
  "openQuestions": 0
}
```

**Why this size.** M: `HostPullRequestReader`'s answer handling and its reasons, which cross varlink in
`HostPullRequestsRefused` and in the read's answer, which the window renders, with their tests. Estimate: 63
minutes (Core's median for M).

**What must be true.** When the review list of a carried project is refused, the person and whoever helps them can
tell from the answer alone whether the token was refused, the person may not read a repository, the repository is
not on the host forge, or the forge failed, and what to do about each; a repository whose first copy the host has
not made yet is no refusal and does not ask the person for anything.

## Why

`HostPullRequestReader.http` answers every non-2xx status with one sentence, reason `forge`: "the host forge refused
the review request; check access and renew the person token with porelo host forge-token". Neither the status nor
the path asked is said. Seen on a host: right after `porelo host forge-token` stored a fresh token, the read of a
project carrying three repositories was refused with that sentence, and nobody could tell which repository or why.
The forge answers a private repository a login may not read with 404, as it answers one that does not exist, so a
404 alone does not tell a missing copy from a person not admitted.

## Plan

1. `HostPullRequestReader.Exchange.get` hands back the status beside the body; `http` stops deciding.
2. `request` turns a non-2xx status into a refusal that names the path asked (`/api/v1/repos/<copy>/pulls...`,
   never the host's address or the token) and the status, with a reason chosen by the status:
   - 401: `forge-token` - the token is refused; renew it with `porelo host forge-token`.
   - 403, and 404 for a copy the host made: `forge-access` - the person may not read that repository on the host
     forge; an administrator of the host admits them.
   - 404 for a copy the host has not made yet: no refusal; the repository is left out of this round, and the answer
     says it waits for its first copy.
   - 5xx and anything else: `forge` - the forge failed; ask again later, the status said.
3. One repository's error answer refuses only that repository: the round answers the others' open lists and names
   the refused one with its reason, status and path; a whole-read refusal stays for what concerns every repository
   (settings, vault, token). A repository the host does not carry is never asked, as `repositories` skips it today;
   a test holds that too.
4. `35-gate.varlink`'s comment lists the added reasons; an added reason is not breaking, and the answer gains the
   per-repository refusals as an added field.
5. `doc/` says what each reason means where the pull-request read is described.

## Red-first test

- `HostPullRequestReaderTest` with a fake exchange: 401, 403, 404 and 503 each refuse with their reason, the status
  and the path, and the message never holds the token. Fails today: one reason, no status, no path.
- A 404 for a copy the host has not made: the round answers the other repositories and no refusal. Fails today.
- One of three carried repositories answers 404 or 403: the other two's open pulls are answered, the third named
  with its reason. Fails today: the whole read is refused.
- A project with carried and not-carried repositories: only the carried ones are asked.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, the read of the project that was refused names which repository and why.

## To be checked

- Where the host records that it made a repository's first copy on its forge, so a 404 can be told apart from a
  person not admitted (`OrganizationProjects.Placed`, the carry record); if nothing records it, the plan asks the
  forge as the host's own account instead, and the size is said again.
- How the window renders `HostPullRequestsRefused` reasons it does not know, before a reason is added (asked of the
  window's agent).
