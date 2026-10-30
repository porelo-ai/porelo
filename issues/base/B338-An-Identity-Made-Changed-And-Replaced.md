# B338 — An Identity Made, Changed And Replaced

**Status:** Accepted.
**Size:** L, proposed, operator confirms 2026-10-31 - grew from M (estimated 90 minutes from the median of M builds:
the identity verbs and their publishing to the registry and each host) when `identity show --json` was added for the
interface's identity, with its registry and forge fields.

**What must be true.** `porelo identity create` makes a person's Ed25519 key, for their own agent such as KeePassXC, and
their card. `porelo identity rotate --json` makes a new key with a card signed by both ([B337](README.md#b337)). `porelo
identity revoke --lost --json` makes a new key and card without the old one's signature and names the admins who must
accept it: the registry's owners, each project admin whose access file names the person, and each host admin whose
host's access file names them as a project admin. The places are read from the registry ([B339](README.md#b339)): the
registry itself, each access repository naming them, and each host those access files name for them or whose own access
file names them as a project admin; each host is reached by its admin's Porelo bringing the new state. Both answer
`{"published": [{"where", "status": "done|pending|refused", "why"}]}`, so a person sees where the new card has not
arrived yet.

`porelo identity show --json` answers who the person on this machine is, public values only: `login`, `name`,
`email` (null when the card has none), `fingerprint`, `key`, `created`, `expires`, `predecessor` (its fingerprint or
null), `card` (its text as filed), `agent_holds_key`, `revoked` (the revocation list or the registry names the key),
`registry`: one `{address, filed: "done|pending|refused|absent", revision, may_file, who_may: [{login, name, email}]}`
per registry this machine reads, and `forge`: `{login, key_listed: true|false|null, keys_url}`, null where the forge
lists no public keys. When something is missing it exits 65 with one line `{"reason", "words"}`: `no-identity` (no card
and no key yet), `key-not-in-agent` (the card is there, its key is not in the agent, as when KeePassXC is locked),
`card-refused` (`words` names why, as `card verify` does) or `revoked` (with `who_may`, the admins who may replace it);
66 for an unreadable file. `sole_without_backup` is not answered here: [B346](README.md#b346) adds it.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- `identity create` makes a key and a card that `card verify` accepts.
- `identity rotate` commits a card signed by both keys and answers each place the registry names.
- `identity revoke --lost` answers each place `pending` with the admins named, until one accepts it.
- A place that refuses is answered `refused` with why; the others are still published.
- `identity show --json` answers each field above for a filed card whose key the agent holds, and each `reason` with
  exit 65 when the card, the key in the agent or its trust is missing.
- Seen to fail first: there are no identity verbs today.
