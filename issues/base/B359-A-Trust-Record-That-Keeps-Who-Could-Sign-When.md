# B359 — A Trust Record That Keeps Who Could Sign When

```json
{
  "$schema": "issue-1.json",
  "id": "B359",
  "title": "A Trust Record That Keeps Who Could Sign When",
  "state": "Idea",
  "size": "M",
  "priority": "Low",
  "rank": 43,
  "blockedBy": [],
  "covers": "The organization's trust record keeps who could sign at each change, so its check judges every signer as the walk does"
}
```

**Why this size.** proposed: the owners and host admins of each accepted change kept in the registry's trust record, and
the record's check judging each signer against those of its time.

**What must be true.** The organization's trust record
is checked against the history before it is used, and a record that fails a check is
discarded and the organization walked again.

`organization/git-tree/src/main/java/org/fuin/porelo/organization/gittree/GitTreeStore.java` reuses a record after
`missedTheList` and `unsettled`; it does not check the recorded history before reuse, so this promise is not met.
The checks below must judge who could sign at the time, as the walk does:

- the signer of a change of `owners.yml` or `members.yml` is checked against those allowed before it;
- a host card's signer is checked against the host admins of that time;
- a person who placed another person's card is checked against those allowed at that time, so a later role change
  does not force a fresh walk;
- the list of revoked keys is checked to be the history's, not only not to trust a revoked key.

With the owners and host admins of each accepted change kept in the record, each of these is judged as the walk
judged it, and a card placed by a former owner needs no fresh walk on every read.

Out of scope, by the same decision: hardening the record against an attacker who writes the person's state
directory, who can change Porelo's config and binaries too
([decision][trust-record-decision]).

## Acceptance

- A record whose `owners.yml` change is signed by a key that was no owner before it is discarded, and the fresh walk
  refuses that change.
- A record whose host card is signed by a key that was no host admin then is discarded.
- A card placed by an owner who owns the registry no more passes the record's check, and a second read walks only
  the commits after the record's.
- A record that leaves a revoked key out of its list is discarded.
- Seen to fail first: all four requirements are unheld by the record today.

[trust-record-decision]:
../../doc/decisions.md#an-organizations-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory
