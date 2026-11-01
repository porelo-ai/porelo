# B356 — A New Key Made Straight Into KeePassXC

**Status:** Review.
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

## Done

- `--keepassxc DATABASE [--entry GROUP/NAME]` on `identity create`, `rotate` and `revoke --lost` (`KeePassXcKey`),
  as `doc/commands.md` says it. The key is made in memory (`SigningKey.generate`) and written in OpenSSH's
  unencrypted private form in Java (`OpenSshPrivateKey.write`, read back by `ssh-keygen -y` in its test).
- `keepassxc-cli` reads the master password from its standard input, and a second attachment on `/dev/stdin` is
  lost in its buffer (measured with keepassxc-cli 2.7.10 on a throwaway database), so each attachment goes over
  its own pipe: Porelo runs `sh -c 'exec 3<&0 <"$1" >>"$2" 2>&1; shift 2; exec "$@"'` with the bytes on the
  shell's standard input, which the shell moves to descriptor 3; it then reopens Porelo's own terminal
  (`/proc/<pid>/fd/0`) as standard input and its error output (`/proc/<pid>/fd/2`) for both outputs, and execs
  `keepassxc-cli attachment-import DATABASE ENTRY id_ed25519 /dev/fd/3`. No file holds the key and no argument
  carries it. `keepassxc-cli` runs three times (`add`, then each attachment), so the password is asked three
  times. The agent settings are UTF-16 with its byte order mark, as KeePassXC writes them: allowed, added at
  open, removed at close, from the attachment, `SaveAttachmentToTempFile` false.
- The terminal and the agent are asked before anything is made; a refusal of `add` hands no key over. The wait
  polls the agent every 250 ms and is a field of `KeePassXcKey`, which the tests set. `key-not-served` also
  answers `entry` and `fingerprint`, so a retry names the key with `--key` and makes no second one; a
  `keepassxc-cli` that is not found is `key-store-missing`, and an entry path with an empty part or a leading `-`
  is 64. `revoke --lost --dry-run --keepassxc` makes no key and answers the places.
- Tests with a stand-in `keepassxc-cli` and a real `ssh-agent` (`IdentityKeePassXcTest`, `IdentityRotateCommandTest`):
  the stand-in notes its arguments, whether each attachment is a pipe, and while it holds the key looks for a
  private key file of it under home, config, state, data, runtime and the JVM's temporary directory; it then
  gives the key to the agent at once, a second later or never, or refuses the password.
- Not claimed: no run against a real KeePassXC; whether KeePassXC serves a key added by `keepassxc-cli` while it
  has the database open without a lock and unlock is not measured. The entry `add` made stays when an attachment
  is refused afterwards; the words say to remove it. A private key held in a Java array is cleared after the hand
  over, the `SigningKey` and the stream buffers are only dropped.
- After its one reading (agy Gemini, 2026-10-31): the wait finds the new key among keys of any kind the agent
  holds (an RSA key beside it crashed the wait before); `key-not-served` says to lock and unlock an open database;
  `create` without `--json` names its entry; the written key is read back by `ssh-keygen -y` for comments of every
  length modulo 8. Its other findings are in the room's list of 2026-10-31T23:52Z, not built tonight; whether an
  open database serves a new entry is filed as an Idea of its own.
