# B340 — A Host Checks The Registry It Was Brought

**Status:** Accepted.
**Size:** S - estimated 136 minutes from the median of S builds: one verb over the registry rule, run as `verify` runs.

**What must be true.** `porelo registry verify --repository <bundle> --host <host> --pinned <host admin fingerprint>
--json` checks a registry brought to the host as a bundle of the whole repository. Without network it cannot ask the
forge, so its anchor is the pinned host admin alone: the host's card and access file must be signed by that key or a
change of key from it, and a person's card is taken only for a login the host's access file names, against the
fingerprint it pins there, or a change of key from it ([B337](README.md#b337)). `owners.yml` and every other path are
not read. It answers `{"commit", "host_card", "admins", "projects": [{"name", "admin", "access_repository"}], "persons":
[{"login", "ssh", "expires", "revoked"}]}`, the host card checked as [B349](README.md#b349) does. It exits 65 as
`verify` does, and needs no network, no home and no vault, so root's sync runs it in the same isolated unit.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A bundle whose host files were signed by the pinned host admin is answered as above.
- A bundle with a host file signed by another key exits 65, naming commit and path.
- It answers the same with `HOME` unset, no vault and no route to any network.
- A card whose key is not the fingerprint the host's access file pins for that login is answered `revoked: true`.
- Seen to fail first: there is no `registry verify` today.
