# B345 — A Host Vault Opened By The Person's Key

**Status:** Implementing. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** L - several days, no median of L builds measured: an SSH agent client, a vault made without a passphrase, the
share held in locked memory by the daemon, `host unlock` on the person's side, and every command that assumes a
passphrase today; grew from M when planned.

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

## Plan

1. `vault`: `SshAgentClient` asks the agent at `SSH_AUTH_SOCK` for its identities and for a signature (requests 11
   and 13, the counterpart of `SshAgentServer`), bounded frames, the key named by its fingerprint; `SshSignature` gets a
   signing seam an agent can serve. `HostVaultShare` signs the exact bytes `host=<fingerprint> account=<login>` in
   the `porelo-vault` namespace and derives 32 bytes by HKDF-SHA256 over the binary SSHSIG blob, empty salt, info
   `porelo-vault-share-v1`, with a fixed test vector.
2. `VaultFile`, `VaultHeader`, `Keyslot`: a slot kind `ssh-agent`, dispatched by kind, and a way to make a vault whose
   only slot it is; the recovery and rekey paths that make a passphrase slot stay out of it.
3. `vault init --share-stdin` and `vault unlock --share-stdin` read exactly 32 bytes and end of input, refuse a
   terminal, never decode them as text; a share no slot takes is its own outcome, exit 77 and `{"vault":
   "other-key"}`, kept apart from a damaged file.
4. The share's owner on the host: `porelod` holds it in a `LockedSecret` (native memory, `mlock`, no dump, zeroed on
   release), replacing the kernel keyring for this kind; lock and clear reach it; `LoginRenewal` and `vault serve` ask
   the owner instead of keeping an opener of their own.
5. `porelo host unlock <host>` (`HostCommand`, `HostUnlockCommand`): the host key checked against the host's card,
   the share derived locally, sent as bytes on the SSH connection's standard input without a terminal or agent
   forwarding; `Command` gains binary input.
6. Every command that asks for a passphrase today - `vault list`, `put`, `remove`, `import`, `authorize`, `agent`,
   `login`, `passphrase`, `setup` - takes the common opening path and, on a share-only vault, names `host unlock`.
7. Tests, red first: `VaultShareStdinTest` - `vault init --share-stdin` is an unknown option today. Then the agent
   client against a fake agent, the derivation's vector and its separation by host, account and key, exit 77,
   rotation keeping entries, a declined reset, lock clearing the owner. Docs: `doc/credentials.md`, `30-vault.varlink`.

## To be checked

- That locked memory covers every copy of the share, the cryptography's included, and works in the native image.
- A changed host key or login changes the share: the vault then opens only through a reset. Whether the host key's
  fingerprint belongs in the signed text at all, or the host's name from its card.
- A daemon restart loses the share as a reboot does; the vault then waits for the next connection.
- Real OpenSSH and KeePassXC agents, with and without confirmation per use.

## Acceptance

- A vault made with `--share-stdin` opens with the same share and with no other, and holds no passphrase slot.
- A share from another key exits 77 with `{"vault": "other-key"}`; after the yes, the vault is cleared, made with the
  new share and opens with it.
- A change of key with both keys at hand keeps every entry.
- `host unlock` sends 32 bytes and no signature through the connection.
- Seen to fail first: today keyslot 0, the passphrase, is always present and `--share-stdin` is unknown.
