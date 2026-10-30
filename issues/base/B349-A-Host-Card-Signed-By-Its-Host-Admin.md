# B349 — A Host Card Signed By Its Host Admin

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
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

## Plan

1. `vault`: `HostCard` beside `PersonCard`, read from SnakeYAML's parser events as it is - one block of fields, no
   anchor, alias, tag or merge key, no control character - with `services` the one nesting allowed: `forge`
   (`web_port`, `git_port`) and `matrix` (`port`), nothing else at any level. `host` a lower-case DNS label, `address`
   a host name or IP address, every port a plain integer 1..65535 as written, `ssh_host_key` an OpenSSH public key
   line whose blob names its own type (`ssh-ed25519`, `ecdsa-sha2-nistp*`, `ssh-rsa`). Exactly one signature in the
   `porelo` namespace; the body names no signer, so the signature's own key is checked against the fingerprint the
   reader trusts, and "another signer" and "does not verify" are told apart.
2. `app-vault`: `card make-host` writes the card to standard output, signed with an unencrypted Ed25519 key file
   (`--key FILE`, through `OpenSshPrivateKey`); signing through `ssh-agent` needs an agent client Porelo does not have
   yet and is left open. `card verify-host CARD --signer SHA256:... [--json]` answers every field and `signed_by`,
   exit 65 naming why, 66 for a file it cannot read; no network, home or vault.
3. `doc/commands.md`: both beside `card verify`.
4. Tests, red first: `HostCardTest` (vault) and `CardHostCommandTest` (app).

## Done

- `HostCard` (vault) reads a host card from SnakeYAML's parser events: one block of fields, `services` the one
  nesting, no anchor, alias, tag, merge key, duplicate key or control character. The host is a lower-case DNS label;
  ports are plain integers 1..65535.
- The address is a host name or an IP address written one way only. IPv4 is four plain decimal parts; IPv6 is hex and
  colons, with no zone, brackets or IPv4 part. A name whose last label is digits or `0x` hex is refused, since a
  resolver would read it as an address.
- `ssh_host_key` is checked field by field, as `ssh-keygen` takes it:
  - Ed25519: 32 bytes;
  - RSA: positive minimal mpints, an odd exponent above 2, 2048 bits at least;
  - ECDSA: the curve named twice and an uncompressed point on that NIST curve, coordinates below p;
  - nothing after the last field.
- The signature is checked as `ssh-keygen -Y verify` checks it:
  - one signature, armored on lines of its own;
  - canonical, padded base64;
  - nothing reserved, nothing after the SSHSIG blob or after its inner Ed25519 signature.

  These checks sit in the shared `SshSignature`/`SigningKey`, so person cards get them too. Another signer and a
  signature that does not verify are told apart.
- `card make-host` signs with an unencrypted Ed25519 key file. It names the file it cannot read and cleans the key's
  own words. Signing through `ssh-agent` is [B351](README.md#b351).
- `card verify-host --signer [--json]` answers every field and `signed_by`. It exits 65 naming why and 66 naming the
  file.
- The ports: `ssh_port` is the host's own. The host serves the forge and the homeserver on sockets only, so
  `services.forge.web_port`, `git_port` and `services.matrix.port` are the local ends of a person's tunnel to them.
  All three are required.
- Tests: `HostCardTest` (27) and `CardHostCommandTest` (13). Each fix of three readings was seen red, by undoing it.
  The sprint 6m rehearsal (step C2) made, accepted and refused a card with real `ssh-keygen` keys.

## Acceptance

- A host card signed by the named host admin is answered with every field.
- A card signed by another key, or with a host name such as `Walk_9`, exits 65 naming why.
- `card make-host` writes a card that `card verify-host` accepts with the maker's key.
- Seen to fail first: there is no host card today.
