# B356 — A New Key Made Straight Into KeePassXC

**Status:** Backlog (operator, 2026-10-31), sprint 6m.
**Size:** S, proposed - one option on three verbs: the key's OpenSSH form in memory, two attachments handed to
`keepassxc-cli` over pipes, and a wait for the agent to serve the key.

**What must be true.** A person who has no key yet gets one that lives in their KeePassXC database and nowhere else.
`porelo identity create`, `identity rotate` and `identity revoke --lost` ([B338](README.md#b338)) take `--keepassxc
DATABASE [--entry GROUP/NAME]`. Porelo makes the Ed25519 key in memory and adds an entry to the database with
`keepassxc-cli`: the private key as the attachment `id_ed25519` and the agent settings as `KeeAgent.settings` (add to
the agent when the database is opened or unlocked, remove it when it is locked), both handed over a pipe, never as a
file and never on a command line. `keepassxc-cli` asks for the master password on the person's terminal itself; Porelo
never sees it. Porelo then forgets the key and waits up to 60 seconds for the agent `SSH_AUTH_SOCK` names to serve it,
and only then makes and signs the card through the agent, as for a key the agent already holds.

It answers as those verbs do, with `"entry"` and `"agent": "holds"`. Without a terminal it is refused with 64. When
KeePassXC took the entry but its agent does not serve the key in time - the database not open in KeePassXC, or its SSH
agent integration off - it answers 65 `{"reason": "key-not-served", "words"}`, keeps the entry and makes no card; a
Retry reads `identity show`, never makes a second key. A `keepassxc-cli` that refuses (a wrong password, an entry that
exists) is answered 65 with `"reason": "key-store-refused"` and nothing is kept by Porelo.

Michi's decision of 2026-10-31 (operator): a new key goes straight into the KeePassXC database through `keepassxc-cli`,
the master password typed in a terminal of its own, and no key file is written anywhere; a key held only in the agent
is not enough, since it is lost at restart. KeePassXC from the deb `keepassxc-full`, not the snap, which cannot reach
the agent socket under `/run/user`. The person's interface opens the terminal, as it does for the forge's sign-in.

## Acceptance

- A key made with `--keepassxc` is served by the agent once KeePassXC has the database open, and the card it signs
  passes `card verify`.
- No file under the account's home, the temp directory or the state directories holds the private key at any time,
  and no process's arguments carry it.
- Without a terminal, with the database not open in KeePassXC, or with a refused password, the answer is the typed
  refusal above, and no card is made.
- Seen to fail first: `identity create --keepassxc` answers `key-store-missing` until this is built.
