# B342 — Persons In The Access File By Their Cards

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: a contract change: the access file, `verify --json` and
its readers, with docs.

**What must be true.** The access file names the project's persons by login, each with the fingerprint of their key,
their role and the hosts they work on. Their cards lie beside it in the access repository, `cards/<login>.card`, each
checked as a card is ([B337](README.md#b337)) against the fingerprint the access file pins. A lost key is replaced by a
project admin who puts the new fingerprint in the access file and the old one under `revoked`. A project admin may mark
a person `locked` for the project; their account and other projects stay, and removing the mark lets them back in. The
project's signers stay in the project file, as the project admins' keys. The WireGuard fields, `ca_sha256` and the
laptops go from the access file.

`porelo project verify --json` answers `"persons": [{"login", "ssh", "role", "hosts", "expires", "revoked", "locked"}]`
from the cards, `role` being `admin` or `developer` and any other value refused; `"admins"`: the logins of the persons
whose role is `admin`, neither `locked` nor `revoked`, in the access file's order, for a host's room admins; and
`"certifiers"`: the key of every admitted person neither `locked` nor `revoked` as a `cert-authority`
line, limited to the tasks' namespaces and principals, since whoever starts a task certifies its key. A host builds its
accounts and key list from the verified commit only. A person whose card fails is answered `revoked: true` and named.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- An access file with persons and cards is read; `verify --json` answers each person and the certifiers as above.
- `admins` names only persons with role `admin`; a locked or revoked admin is left out; a role other than `admin` or
  `developer` is refused, naming it.
- A card whose key is not the fingerprint the access file pins, or that fails its check, makes that person `revoked:
  true`, named on a line of its own; the others are answered.
- A fingerprint moved under `revoked` by a signed commit makes that person's old card `revoked: true` and their new one
  answered.
- An access file still naming WireGuard fields or laptops is refused, naming the key.
- A person marked `locked` is answered `locked: true` for this project and is no certifier, so their tasks' commits are
  refused; unmarked `false`.
- Seen to fail first: today's access file requires the WireGuard fields and names no person by a card.