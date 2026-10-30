# B345 — A Host Vault Opened By The Person's Key

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: a new kind of keyslot, the share derived on the person's
side and taken on the host's, and the vault's own refusal.

**What must be true.** A person's vault in their host account is opened by a share derived from their key: HKDF-SHA256
over the SSH signature, namespace `porelo-vault`, of `host=<host key fingerprint> account=<login>`, made by the person's
local agent on their own machine. `porelo host unlock <host>` derives it there and sends only its 32 bytes through the
SSH connection to `porelo vault unlock --share-stdin`, or to `vault init --share-stdin` for a new vault; the signature
never leaves the person's machine. The share is the vault's only keyslot; there is no passphrase. The host's Porelo
keeps it in locked memory only, no swap and no core dump, until a reboot or a lock, so sign-ins are renewed while the
person is away; after either the vault waits for the person's next connection.

A key that does not open the vault - a lost key replaced - gets the vault's own answer: exit 77 and, with `--json`,
`{"vault": "other-key"}`, not a wrong passphrase. On the person's yes the vault is cleared and made again with the new
key; the sign-ins are then made once more. A change of key with the old key still in hand enrols the new share while the
old one opens, then removes the old one, and nothing is lost. The slot says what protects it: any process running as the
person with the agent can derive it, unless the agent asks to confirm each use.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A vault made with `--share-stdin` opens with the same share and with no other, and holds no passphrase slot.
- A share from another key exits 77 with `{"vault": "other-key"}`; after the yes, the vault is cleared, made with the
  new share and opens with it.
- A change of key with both keys at hand keeps every entry.
- `host unlock` sends 32 bytes and no signature through the connection.
- Seen to fail first: today keyslot 0, the passphrase, is always present and `--share-stdin` is unknown.
