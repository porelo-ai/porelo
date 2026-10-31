# B345 — A Host Vault Opened By The Person's Key

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** L (operator, 2026-10-31) - several days, no median of L builds measured: an SSH agent client, a vault made without a passphrase, the
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

## Done

- The person's local agent derives a share for the host fingerprint and account, and only the share crosses
  SSH. The daemon retains it in locked native memory excluded from dumps; its watches use that same owner.
  Every process of the account can read it, locally or over SSH, including `porelo daemon connect`, just as it
  can ask the agent to sign. The account uid is the boundary, and `ssh-add -c` can require confirmation per use.
- A host vault contains only SSH share slots. Passphrase, device-only and mixed vaults are refused as
  `not-a-host-vault`, exit 65, before key matching. `host unlock --yes` preserves them. `other-key`, exit 77,
  means only that no slot in a host vault opens with the supplied share. Both refusals have named daemon errors.
  Device enrollment cannot add another way into a host vault, and the last SSH share cannot be revoked.
  A generic device opener never unwraps an SSH slot in a mixed vault or caches its SSH share in the keyring.
  Device-only and mixed vaults report their current device from the keyring in every keyslot reply.
- `host unlock` checks the host key, disables forwarding and connection reuse, and requires a single JSON
  status line whose exit agrees. Verified absence initializes the vault. Another key permits a reset only
  after a terminal yes or explicit `--yes`, and the person is told to sign in again. Unexpected output or a
  mismatched exit stops the exchange with 70 and displays the remote stderr without control characters.
  Reset also verifies the clear command's JSON outcome and exit before sending the share again, and an init
  is believed only when it answers the vault open.
  `--remote-porelo` selects an account installation; host fingerprint and login remain explicit inputs.
- With both keys available, `--key NEW_FP --previous-key OLD_FP` derives both shares locally and sends the old
  32 bytes followed by the new 32 bytes to `vault rekey --share-stdin`. The host verifies the old share and
  atomically replaces its opening slot with the new one while preserving every entry. A rejected previous
  share changes nothing, even when the person's command has `--yes`.
- The owner binds an unlock to the resolved path and the opening slot's wrapped-key digest. Detecting a
  replacement releases the old share immediately and invalidates cached entries; restoring the old file
  cannot reopen it. A failed keep releases what was held. Lock and forget win over verification already in
  progress. Replacement closes the previous locked allocation before another can be retained.
  Refusing a keep with malformed base64 releases the previously held share too, and so does a wrong share:
  any keep the owner refuses leaves the host vault locked, which the account could do with lock anyway. An
  unlock refused before its share reaches the owner - no vault file, no host vault, wrong use - leaves a held share
  as it was: only the account itself can swap its vault file, and that is inside the uid boundary.
- Both ends check the kernel peer uid before exchanging a share. The socket parent must belong numerically
  to this uid and cannot be a symlink; neither can the socket itself, which is looked at after its directory. Every
  directory the path passes on the way to it, followed as the kernel follows it (`..` from where a link led, each
  link by its own target name by name, a target's slashes as readlink gives them, at most 40 links), must belong to
  this uid or root, as must each link, which must also lead somewhere; below a name that does not exist yet only
  names may follow. Others may write to none of these directories without the sticky bit; group write is allowed on
  the account's own directory under a group of its own and on root's under root's group. An owner a user namespace
  shows as overflowuid is refused, since that is every other account too. Each directory is judged without following
  a link, and a directory that cannot be read is refused without naming where a link led. The check runs again once
  the socket's directory is made. The path is bound as given, relative too; the socket must have a name, and its
  directory is named by its own name on both ends, so a trailing `.` cannot hide a link and a trailing `..` is
  refused. Not claimed by a test: the root-group allowance, a link of another account, the second check and a
  directory swapped for a link while it is judged, which need a root-owned directory, a second account and a race;
  that the directory is private before the bind, and that a relative path is bound relative; the tests that name
  another uid skip where a directory above the test's is not root's. Forget reports a missing socket explicitly. Lock and clear accept an
  unreachable owner as holding nothing only after establishing that no porelod is running for this uid;
  otherwise they refuse and name the expected socket path.
- Lock and clear release the host share and invalidate the decrypted conversation cache even when the file
  is missing, moved, replaced or malformed. Lock erases both local keyring entries before its bounded owner
  request and never reads the encrypted file afterwards. Clear refuses deletion if forgetting cannot be
  established. A running task's broker still holds the credential it read until that task ends.
- Explicit share, signature, agent-frame and parsing arrays are cleared on success and failure. Vault writes
  reuse an already-open master key rather than copying the same share from its owner again.

- Tests: `HostVaultSocketTest` (30), `HostVaultOwnerTest` (27), `HostUnlockReplyTest` (18), `HostVaultConnectionTest`
  (15), `HostUnlockCommandTest` (14), `VarlinkServerSocketTest` (14), `HostVaultKindTest` (7), `LockedSecretTest` (6),
  `HostVaultScopeTest` (5), `VaultGrantOwnerTest` (2), `SocketPeerTest` (1), `HostVaultKeepTest` (1); the whole suite
  4404 green. Twelve rounds of narrow readings by agy Gemini.

Residuals: varlink JSON and base64 shares, signature armor and parsing strings, and strings holding decrypted
entries cannot be zeroed. Bouncy Castle's HKDF parameters clone the signature into library-owned memory; its
HKDF generator, Ed25519 signing and verification state, ciphers, and SSH and wire encoding can retain additional
library-owned buffers. Clearing explicit arrays and locking the retained native allocation cannot erase these
copies or protect every temporary copy from swap or dumps.

## Acceptance

- A vault made with `--share-stdin` opens with the same share and with no other, and holds no passphrase slot.
- A share from another key exits 77 with `{"vault": "other-key"}`; after the yes, the vault is cleared, made with the
  new share and opens with it.
- A change of key with both keys at hand keeps every entry.
- Passphrase, mixed and device-only vaults answer `not-a-host-vault`, exit 65, and stay unchanged even with `--yes`.
- `host unlock` sends 32 bytes, or 64 old-then-new bytes with `--previous-key`, and no signature through the connection.
- Seen to fail first: today keyslot 0, the passphrase, is always present and `--share-stdin` is unknown.
