# B617 — An Undecided Organization Read Says What It Waits For

```json
{
  "$schema": "issue-1.json",
  "id": "B617",
  "title": "An Undecided Organization Read Says What It Waits For",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "A walk the identity source cannot decide keeps its words: `stale_why` `incomplete` names the commit that waits and what it waits for (the forge's key list, the founder's ownership, a card not vouched for) in `said` and a warning."
}
```

**Why this size.** the reason crosses four layers: the walk keeps the first undecided step's words and commit,
`GitTreeStore` and `GitTreeOrganization` carry them on the answer (`OrganizationQuestions` gains a default
`waiting()`), `IdentitySource` gains a default `cannotSay()` that `HttpForgeDirectory` sets at its nine "cannot say"
points, and `OrgReading.display` answers them; unit tests in git-tree and in the display. Filed as S; analyzed, it is
M.

Filed 2026-11-11 from the window proof on 0.5.1~snapshot.484. Accepted, analyzed, estimated and set to Backlog by
the operator 2026-11-11, before [B616](README.md#b616).

**What must be true.** `porelo org --json --refresh` on a machine whose organization cannot be decided says why. On the
VM it answered `stale: true`, `revision: null`, `checked_at: null`, `stale_why {cause: incomplete, said: null}`, and
no warning or refusal, although the fetch worked. A person cannot tell from that whether the forge's key list was out
of reach, the founder's ownership could not be read for want of a token, or a card waits to be vouched for.

How it is today, read in the code:

- `TreeWalk` throws `Undecided` with words ("the identity source could not say who founded it", "... whose card this
  is", "... has not vouched for the signer's card"). Where the walk catches it, it falls back to the state before
  and returns not whole, and the words are dropped.
- `HttpForgeDirectory` answers "cannot say" for a failure, a timeout, a rate limit, another status, and for an
  ownership a missing or blind token. That is right, since a forge being down must not refuse a person, but nothing
  of which one it was reaches the answer.
- `OrgReading.display` turns a reading that is not whole into `cause: incomplete` with `said: null`.

## Analysis

Read on `main` at 3396b7108.

- **Where the words are lost.** `TreeWalk.commit` catches `Undecided` for a path, restores the model before the commit
  and returns `false`. The walk stops there, and `GitTreeStore.walkLocked` computes `whole` as "the walk reached the
  tip". Nothing of the `Undecided` reaches `answers(walked, warnings, whole)`.
- **The founding has two causes behind one sentence.** `trust(founder, signer)` answering `FIRST_SEEN` means the forge
  did not say whether the signer's key is the founder's. `owns.isEmpty()` means it did not say that the founder owns
  the organisation, which on GitHub needs a token that sees the members. Both throw "the identity source could not say
  who founded it". They get separate words.
- **Why the directory could not say.** `HttpForgeDirectory` returns `Optional.empty()` at nine points: no answer or a
  timeout, a rate limit (403 or 429 with the limit spent), another status, an answer too long or that does not parse,
  a redirect, no token for a membership, and a token that cannot see the organisation's members. `IdentitySource`
  has no way to say which. A default `cannotSay()` beside `keys` lets the directory keep the kind of its last "cannot
  say", in its own words and never the forge's body. Stand-ins answer nothing, as now.
- **The answer.** `OrganizationQuestions` gains a default `Optional<Waiting> waiting()` (the commit, the step's
  words, the directory's kind). `GitTreeOrganization` carries it when the walk did not reach the tip, and adds the
  same words to its warnings. `OrgReading.display` fills `stale_why.said` from it for `incomplete`: "waits at
  <short commit>: <words> (<kind>)".
- **A kept record keeps it too.** An incomplete walk may be kept (`keepIncomplete`). Its waiting step is written
  into the record, so that a kept display can still say it.
- **No secret reaches it.** The words are Porelo's own, the commit is a hash, and the kind is one of the fixed words
  above. Nothing the forge answered is quoted, and no token, login of a token or URL with a query is quoted either.

## Plan

- `TreeWalk` keeps the first `Undecided` (commit, words); the founding's two causes get their own words.
- `IdentitySource.cannotSay()` (default empty); `HttpForgeDirectory` sets it at each "cannot say" point, with fixed
  words: `no answer`, `rate limited`, `another status`, `an answer that does not read`, `no token for the
  organisation's members`, `the token cannot see the organisation's members`.
- `OrganizationQuestions.waiting()` (default empty); `GitTreeStore` and `GitTreeOrganization` carry it, add it to the
  warnings, and keep it in the record of an incomplete walk.
- `OrgReading.display` answers it in `stale_why.said` for `incomplete`, and the text form's "Stale because:" line.
- Red first:
  - git-tree: a fixture whose founding the identity source cannot say answers `waiting()` with the founding commit
    and the words, once for the key and once for the ownership;
  - `HttpForgeDirectory` against a stand-in server: a 403 rate limit and a membership without a token each give
    their kind;
  - the display test answers `said` and a warning, and no answer body is quoted.
- `doc/commands.md`, the `org` section: what `said` holds for `incomplete`. Changelog under *Changed*.

## Estimate

M, est=79 (p80 129) by the median velocity. There are no open questions.
