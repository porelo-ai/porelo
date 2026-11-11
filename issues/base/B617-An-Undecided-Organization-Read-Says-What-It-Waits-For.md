# B617 — An Undecided Organization Read Says What It Waits For

```json
{
  "$schema": "issue-1.json",
  "id": "B617",
  "title": "An Undecided Organization Read Says What It Waits For",
  "state": "Idea",
  "size": "S",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "A walk the identity source cannot decide keeps its words: `stale_why` `incomplete` names the commit that waits and what it waits for (the forge's key list, the founder's ownership, a card not vouched for) in `said` and a warning."
}
```

**Why this size.** the walk keeps the words and commit of the first undecided step, the store's answer carries them,
`OrgReading.display` puts them in `stale_why.said` and a warning; unit tests over the git-tree fixture and the display.

Filed 2026-11-11 from the window proof on 0.5.1~snapshot.484.

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

## Plan

- The walk keeps the first `Undecided`: its words and the commit it stopped at. The store's answer carries them
  beside `whole()`.
- `OrgReading.display` gives `stale_why.said` those words with the commit's short hash, and adds them as a warning.
  The text form's "Stale because:" line shows them.
- `HttpForgeDirectory`'s "cannot say" names its kind where it is known without quoting the forge: `no answer`,
  `rate limited`, `no token for the organisation's members`, `the token cannot see the organisation's members`.
  `Undecided` quotes that kind.
- Red first: a git-tree fixture whose founding the identity source cannot say answers the words and commit; the
  display test answers them in `said` and a warning; nothing the forge answered is quoted.
- `doc/commands.md`, the `org` section; changelog under *Changed*.
