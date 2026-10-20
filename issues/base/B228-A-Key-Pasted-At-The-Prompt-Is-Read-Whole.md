# B228 — A Key Pasted At The Prompt Is Read Whole

**Status:** Analyzed.
**Size:** S - the prompt's read in `VaultPutCommand` and one refusal in `StoredKey`, each with its test; no contract.

**What must be true.** A private key pasted at `porelo vault put`'s prompt is stored as the same key `--from-file` or
standard input would store, and a key that cannot be read is called unreadable, never a key of the wrong algorithm.

## Why

At a terminal the value is read with `Console.readPassword`, one line (`VaultPutCommand.readValue`), while a piped
value is read whole once its first line is armour. A pasted key therefore arrives as its `-----BEGIN OPENSSH PRIVATE
KEY-----` line alone; `OpenSshPrivateKey.describe` cannot read it, and `StoredKey.fromFile` then reports "an
OpenSSH-wrapped key that is not Ed25519" and suggests a conversion that cannot help.

The same refusal tells the person to "use it where it lies with --file"; `vault put` has no `--file`, its option is
`--from-file`.

## Plan

Read in the code on 2026-10-20 (`apps/app-vault`, `vault`).

1. **`VaultPutCommand.readValue`** (`:435`): at a terminal, a first line starting with `-----BEGIN` is followed by
   further `readPassword` calls with no prompt, each appended, until a line starting with `-----END`; the piped
   branch (`:455`) already reads on in the same way. Bounded at 200 lines: a paste that ends, or runs past the
   bound, without its `-----END` line is refused as incomplete, naming the lines read, and nothing is stored. The
   lines are wiped as the single line is today.
2. **`StoredKey.fromFile`** (`:72`): `OpenSshPrivateKey.describe` answering `null` is its own refusal - "cannot be
   read as an OpenSSH key; it is incomplete or damaged" - before the algorithm is judged; "not Ed25519" stays for a
   key that was read and is another algorithm.
3. **The refusal names `--from-file`**, the option `vault put` has.

**Red first:**
- `VaultPutCommandTest`: a terminal handing an Ed25519 key line by line stores the same value as the piped key does;
  a terminal handing `-----BEGIN` and then nothing is refused as incomplete. Today the first stores the armour line
  alone.
- `StoredKeyTest`: the armour line alone is refused as unreadable, not as "not Ed25519".

## Acceptance

- A key pasted at the prompt - Ed25519, and an OpenSSH-wrapped RSA key, converted as `--from-file` converts it - is
  stored as `--from-file` stores the same file, measured on the ubuntu VM in a real terminal.
- A truncated paste is refused as incomplete, and nothing is stored.
