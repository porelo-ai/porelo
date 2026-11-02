# B359 — A Trust Record That Keeps Who Could Sign When

**Status:** Idea.
**Size:** M, proposed - the owners and host admins of each accepted change kept in the registry's trust record, and
the record's check judging each signer against those of its time.

**What must be true.** The trust record `porelo registry read` keeps
is checked against the history before it is used, and a record that fails a check is
discarded and the registry walked again. Some checks need to know who could sign at the time,
which the record does not keep, so today they are weaker in the record than in the walk:

- the signer of a change of `owners.yml` or an access file is bound only by the commit's own verified signature, not
  checked against the owners before it;
- a host card's signer is checked to be the card's own signer, not a host admin of that time;
- an owner who placed a person's card is checked against today's owners, so a card placed by a former owner fails
  the check, and each read of that registry walks it afresh until the person files their own card;
- the list of revoked keys cannot be derived from any file, so the record is checked only not to trust a revoked
  key, not that the list is the history's.

With the owners and host admins of each accepted change kept in the record, each of these is judged as the walk
judged it, and a card placed by a former owner no longer costs a fresh walk on every read.

Out of scope, by the same decision: hardening the record against an attacker who writes the person's state
directory, who can change Porelo's config and binaries too
([decision](../../doc/decisions.md#a-registrys-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory)).

## Acceptance

- A record whose `owners.yml` change is signed by a key that was no owner before it is discarded, and the fresh walk
  refuses that change.
- A record whose host card is signed by a key that was no host admin then is discarded.
- A card placed by an owner who owns the registry no more passes the record's check, and a second read walks only
  the commits after the record's.
- A record that leaves a revoked key out of its list is discarded.
- Seen to fail first: each of the four passes the record's check today.
