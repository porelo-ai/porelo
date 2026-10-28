# B214 — A Vault Entry Put Again Unchanged

**Status:** Implementing.
**Size:** M - two modules (`vault`, `app-vault`) and a `--json` answer porelo-host's play reads, with a row in `doc/hosts.md`.

**What must be true.** `porelo vault put NAME` given exactly the value the entry already holds says so and exits 0,
so a playbook that puts its keys on every run can report that nothing changed, without ever comparing a value
itself.

## Why

A host's second play puts each key of the machine file into the vault with `vault put --from-file`. A second `up` on
a host that is up must change nothing and say so (`changed=0`), but `vault put` answers `stored … (N characters)`
with exit 0 whether the entry was new, different or the same. A play must not read the value to compare, so it can
only call every `put` changed.

## The shape proposed

- Porelo compares inside the vault, against the entry it would overwrite. When they are the same it writes nothing
  and says `unchanged NAME`, with nothing of the value shown.
- `--json` gives `{"name", "stored"}`, `stored` false when the value was the same.
- The same for `--from-file` and standard input. A grant's entry, which holds no secret, compares by what it names.

## Plan

Read in `VaultPutCommand`, `VaultFile`, `VaultEntry`, `GrantEntry`, `VaultStatusCommand` and `doc/hosts.md`.

1. `vault put` always calls `VaultFile.update`, which always writes (a fresh nonce, so the file changes), and always
   prints `stored NAME (kind, N characters)`; it has no `--json`. Standard input and `--from-file` end in the same
   `VaultEntry`, and a grant's entry is `VaultEntry("-", kind, settings)`, so a record comparison already compares a
   grant by what it names.
2. **`VaultEntry.sameAs`** compares type and settings, and the value with `MessageDigest.isEqual`, as
   `PhantomToken` does. **`VaultFile.update`** writes nothing when the change left the entries as they were.
3. **`VaultPutCommand`** sees the entry it would overwrite inside the update; when it is the same, it answers
   `unchanged NAME`, exit 0, with nothing of the value. **`--json`** prints `{"name", "stored"}` alone on standard
   output, every other line on standard error.
4. **Docs:** a row in `doc/hosts.md` among the answers a second run reads, and the option in the command's help.
5. **Red first:** `VaultFileTest.anUpdateThatChangesNothingLeavesTheFileByteForByte`, then
   `VaultPutCommandTest.aValueTheEntryAlreadyHoldsIsSaidUnchangedAndWritesNothing`.

**Built, where it departs from the steps above:** `VaultFile.update` answers whether it wrote, and both of its
ways in (passphrase and opener) compare before writing; a rekey always writes. `vault put` reads that answer
rather than looking at the entry itself, so a put over a sign-in that still has a client secret is a change, as the
secret goes with it. The command's own test calls `VaultPutCommand.put` against a vault file, since `vault put`
takes its passphrase from the keyring or a terminal; the output and `--json` are checked in
`vault-commands.feature`.

A reading by a second agent changed: `--from-file` now gives the value standard input would, stripped and an empty
grant's file a public client (`fileValue`), so the same token by pipe and by an editor's file is one value, where
before a file's newline made every such put a change and stored the newline; the scenario puts by a file ending in a
newline, checks each put's exit and that `--json` leaves exactly one line on standard output, and no longer throws
standard error away; the opener's way is tested with its answer and a change in place; `put`'s documentation sat on
`replace`. Not changed: an ssh key put again still says `read …` before `unchanged`, which is still true of it.

**Decided:**
- A second `put` without `--type` after one that stored a type is a change and overwrites, as now: a put states the whole entry.

## Acceptance

- The same value put twice, by standard input and by `--from-file`, answers `unchanged NAME`, exit 0, and the vault
  file's bytes are unchanged. Seen to fail first: the nonce changes them today.
- A different value, `--type` or `--setting` answers `stored`; `--json` holds `name` and `stored` and no part of the
  value.

## To be checked

- Whether `ssh-keygen -p -m PEM` gives the same bytes for the same RSA key each time; if not, such a key always
  reads as changed. The comparison takes constant time at no cost, though a timing leak is out of reach: only a
  caller who may overwrite the entry can ask.
