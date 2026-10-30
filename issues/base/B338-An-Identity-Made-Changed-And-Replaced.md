# B338 — An Identity Made, Changed And Replaced

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: the identity verbs and their publishing to the registry
and each host.

**What must be true.** `porelo identity create` makes a person's Ed25519 key, for their own agent such as KeePassXC, and
their card. `porelo identity rotate --json` makes a new key with a card signed by both ([B337](README.md#b337)). `porelo
identity revoke --lost --json` makes a new key and card without the old one's signature and names the admins who must
accept it: the registry's owners, each project admin whose access file names the person, and each host admin whose
host's access file names them as a project admin. The places are read from the registry ([B339](README.md#b339)): the
registry itself, each access repository naming them, and each host those access files name for them or whose own access
file names them as a project admin; each host is reached by its admin's Porelo bringing the new state. Both answer
`{"published": [{"where", "status": "done|pending|refused", "why"}]}`, so a person sees where the new card has not
arrived yet.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- `identity create` makes a key and a card that `card verify` accepts.
- `identity rotate` commits a card signed by both keys and answers each place the registry names.
- `identity revoke --lost` answers each place `pending` with the admins named, until one accepts it.
- A place that refuses is answered `refused` with why; the others are still published.
- Seen to fail first: there are no identity verbs today.
