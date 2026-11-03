# B385 — A KeePassXC Key That Is Usable

**Status:** Implementing.
**Severity:** medium.
**Size:** L - one unlock serving three or more `keepassxc-cli` commands without Porelo reading the master password, proved
first against the real program, then `KeePassXcKey`, the three verbs, the stand-in and about a dozen tests; the
smallest piece (typed refusals for a terminal that is not the person's) is S on its own.

**What must be true.** `identity create`, `identity rotate` and `identity revoke --lost` with `--keepassxc DATABASE`
are usable by a person at their terminal:

- The master password is asked once, not once for the entry and once for each attachment.
- A retry of `rotate` or `create` after `key-not-served` takes the key the first run put into the database, and never
  makes a second one.
- No orphaned or empty KeePassXC entry is left: when the entry was added but an attachment is refused, the entry is
  removed again, and the answer says what is left in the database: a private key stays in its recycle bin until the bin is
  emptied.
- Under `su` or `sudo -u`, or with standard error redirected, the answer is a clear refusal that says why, never a
  `keepassxc-cli` that waits on a terminal nobody sees or reads the wrong one.
- A person who never types the master password gets a refusal that says so, not a process killed after ten minutes;
  with neither an agent nor a terminal the answer is `no-terminal`; a re-run with the same `--id` answers its `entry`;
  with `/proc` mounted `hidepid` the refusal says why.
- The three verbs answer the same refusal with the same exit code.

## Plan

Read in `apps/app-vault` and measured against `keepassxc-cli` 2.7.10. Today `KeePassXcKey.make` (`:117-180`) generates the key
(`:193`), then runs `keepassxc-cli` three times through a `sh` wrapper (`:258-276`): `add` and two `attachment-import`, each
asking the password. Nothing removes an entry when an attachment fails (`:239-248`), a second `make` makes a second key, and
only standard input is judged a terminal (`StandardInput.java:45-52`), while the wrapper also appends to standard error. A
timeout reads as exit 127, `key-store-missing` (`:273-275`).

1. **Refusals before anything runs**, exit 64 and typed, in all three verbs: standard input and standard error must be one
   terminal this user can open (`no-terminal`, saying which is not); the terminal must be this user's (`terminal-not-yours`,
   for `su` and `sudo -u`); an unreadable `/proc/self/fd` says so.
2. **One password per create.** One `keepassxc-cli open DB`, the password read by it from the terminal, then `add`, both
   `attachment-import`s and `exit` fed from a pipe with the key and the settings on their own descriptors. Porelo never sees the
   password. Commands sent with the password are lost (measured), so a spike against the real program in a pseudo-terminal
   comes first. If no way that does not depend on timing is found, three prompts stay and the answer says so. The walk's test asks for one
   prompt, so the spike's result is said in the room as soon as it is known.
3. **A retry takes the key its own operation made, and no other.** Once a key is generated and before `add`, the record of the
   `--id` is written with the entry and the key's fingerprint, an optional field of `IdentityOperation`. All three verbs are
   bound this way: the cut gives `create` its `--id`, by the contract's section 3, and without one the answer prints a new id.
   Asked again with that `--id`, `make` reads the recorded entry's key back with `attachment-export --stdout` and takes it only
   if its fingerprint is the recorded one and it is neither the card's current key nor a revoked one; the agent is then waited
   for as in a first run. Everything else is refused, naming the entry: an `--entry` that exists under a new `--id` or no record,
   as `add` refuses it today; a fingerprint that differs; an entry without both attachments. So `rotate --entry <the old key's
   entry>` never rotates onto the key being replaced.
4. **No orphan entry.** A refused attachment removes the entry in the same session with one `rm`, which moves it to the
   database's recycle bin. There is no second `rm`, which would depend on the database's language. The private key stays in
   the bin until the person empties it, and the answer says so, naming the group; or says "removed", for a database with no
   bin; or "not removed: remove <entry> in KeePassXC".
5. **A password never typed.** 120 s per prompt, then `no-password`, exit 64, nothing kept, rather than 127. With neither agent
   nor terminal, `no-terminal` before the agent is asked. A re-run with the same `--id` answers the `entry` of its record.
6. **One refusal, one code.** One method maps `IdentityChange.Refused` to the answer, used by `IdentityCreateCommand.made`
   (`:257-283`) in place of its own `Refused` (`:101-122`).
7. **The stand-in** counts prompts, reads from the terminal path it is given, and watches for a file holding the key while the
   key exists (`WatchService`), rather than one look at `attachment-import`.

**Files:** `KeePassXcKey`, the three `Identity*Command`s, `IdentityChange`, `IdentityOperation`, `StandardInput`; tests
`IdentityKeePassXcTest`, `IdentityRotateCommandTest`, `IdentityCreateCommandTest`, `KeePassXcStandIn`. Built after
[B391](README.md#b391), which rewrites `IdentityChange` and gives `create` its `--id`; line numbers are read again then.

**Red first:** one test per refusal of step 1 through the stand-in, its `arguments` file empty; and for step 3, `rotate`
with `--entry` naming the old key's entry, which today's plan would have adopted.

## As built

Against the plan, simpler, none of it weaker under the threat this guards:

- **Step 3 needs no read-back and no record in `IdentityOperation`.** A note beside the operations holds the id's entry and fingerprint. A retry only waits for the agent to serve that fingerprint: that it serves it proves it is that key, so the private key never comes back into this process and no password is asked. An entry changed since is `key-not-served`, naming it. A record in the operations would have needed `pending` and its readers to skip a half change. The case the plan guards, `rotate --entry <the old entry>`, is an `add` that KeePassXC refuses, under any new id.
- **Step 1 does not ask for one terminal.** Each of the two must be a terminal this account can open. Two different ones is the person's choice.
- **Step 7's `WatchService` gave way to the JVM's own record.** A watcher can read a file only if it is still there, so a key written and deleted at once could pass it. `IdentityKeePassXcTest` records every `jdk.FileWrite` while a key is made, which a file deleted at once cannot escape, and its own test writes a key to a temporary file, deletes it and is caught. It sees this process's writes, not a program it starts.
- **`hidepid` is not measured.** An unreadable descriptor is refused saying so, whatever the cause.
- **Step 6 was done by the cut**, all three verbs answer through `IdentityChange.refuse`.

Left as they are, after the reading: a crash between the note and `add` leaves an id that waits for a key nobody holds, which a new id goes round; a crash after the agent serves the key and before the change is recorded loses the note, and a retry makes a second key or finds its entry taken. An unset `SSH_AUTH_SOCK` still answers 65 `key-not-in-agent` from `create` and 69 from the other two, older than this.

A prompt ended by its limit left the terminal's echo off, measured with the real program: the wrapper now gives it back.

Step 2 is its own commit, held back: it reads the master password in Porelo's own wrapper, which the operator decides.

## Acceptance

- With the stand-in: one prompt for a whole create; a retry with the same `--id` after `key-not-served`, on `create` and on
  `rotate`, makes no second key, exactly one `add`, and waits for the first run's fingerprint; each seen to fail first.
- `rotate` and `create` with a new `--id` and an `--entry` that exists, one holding the card's current key, one a revoked key and
  one any other, are refused with no `add` and the card unchanged; and so is a retry whose entry's fingerprint is no longer the
  recorded one; each seen to fail first.
- Against the real `keepassxc-cli` 2.7.10 in a pseudo-terminal: one create asks once; where the fall-back holds, three times, and
  the answer says so.
- Standard error redirected, a terminal of another account and an unreadable `/proc/self/fd` are refused before `keepassxc-cli`
  starts, with the same reason and code from `create`, `rotate` and `revoke --lost`; each seen to fail first.
- A password not typed in time answers `no-password`, exit 64, nothing kept, not `key-store-missing`; seen to fail first.
- After a refused attachment the stand-in's database, read afterwards, holds no such entry outside the recycle bin, and the
  answer says the private key stays in the bin until it is emptied.
- A re-run with the same `--id` answers the first run's `entry`, plain and as JSON.
- A test writing the key to a temporary file and deleting it before `attachment-import` makes the stand-in's check fail, and the
  redirection of standard input to the terminal is tested without the real program.

## To be checked

- A spike of step 2 in a pseudo-terminal against the real `keepassxc-cli` 2.7.10 (`script -qec`): whether the prompt for
  the password can be left to `keepassxc-cli` and the commands sent after it without a fixed pause (for instance by waiting
  for the `DATABASE>` prompt on its output). Not established; the piped measurement here had no terminal.
- What `hidepid` does to `/proc/self/fd/0` of the process itself (a process may read its own `/proc/<pid>/fd` under
  `hidepid=2`; whether `readlink` of it works for a process started by `sudo -u` or in a container is not established, and
  `/proc` here is mounted without `hidepid`). The refusal for it is written once this is measured, in a user namespace or
  with a remount on a VM.
- What `su` / `sudo -u` do to the wrapper's `exec 3<&0 <"$1"`: the other account cannot open the first account's pts (read,
  not run); measure on a VM with two accounts and see which message `sh` gives, so the refusal's words are the ones the
  person can act on.
- Whether a key read back by `attachment-export --stdout` is byte-identical to the one imported (OpenSSH armour with a
  trailing newline), so the fingerprint read back is the one the agent serves.
- The exit code and message of `rm` for a recycle bin in each language KeePassXC ships, and whether the bin can be switched
  off in a database (then `rm` deletes at once).
- Whether `IdentityOperation` can take an optional `entry` field without a change to the kept operation files other versions
  read (look at `IdentityOperation.read`).
- After the filing move into `organization/git` lands: which of the call-site lines named in the plan have moved.
