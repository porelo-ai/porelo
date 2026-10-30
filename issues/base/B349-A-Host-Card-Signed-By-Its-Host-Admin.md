# B349 — A Host Card Signed By Its Host Admin

**Status:** Implementing. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** S - estimated 136 minutes from the median of S builds: the host card's format and its check, beside the person
card.

**What must be true.** A host card is a YAML file - `host`, `address`, `ssh_port`, `ssh_host_key`, and `services`:
`forge` with `web_port` and `git_port`, `matrix` with `port` - signed by its host admin's key as an SSH signature in the
`porelo` namespace, made on the host admin's own machine by `porelo card make-host --host <host> ...`, since their agent
never reaches the host. `porelo card verify-host <card> --signer <fingerprint> --json` answers its fields, and exits 65
on a bad signature, another signer, a host name that is not a lower-case DNS label, or a port missing. It needs no
network, no home and no vault.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A host card signed by the named host admin is answered with every field.
- A card signed by another key, or with a host name such as `Walk_9`, exits 65 naming why.
- `card make-host` writes a card that `card verify-host` accepts with the maker's key.
- Seen to fail first: there is no host card today.
