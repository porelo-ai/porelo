# B228 — A Key Pasted At The Prompt Is Read Whole

```json
{
  "$schema": "issue-1.json",
  "id": "B228",
  "title": "A Key Pasted At The Prompt Is Read Whole",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 35,
  "blockedBy": [],
  "covers": "A private key pasted at `vault put`'s prompt is read whole, as from a file or a pipe, and an unreadable key is called unreadable, not the wrong algorithm.",
  "openQuestions": 0
}
```

**Why this size.** the prompt's read in `VaultPutCommand` and one refusal in `StoredKey`, each with its test; no contract.

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

## Decided by the operator (2026-11-12)

- To Backlog, priority unchanged.

## Built (2026-11-13)

- **`VaultPutCommand.readValue`** takes a second terminal reader, `typedOn`, which `valueFrom` makes
  `Console.readPassword()` with no prompt. A first typed line starting with `-----BEGIN` is followed by those reads,
  each appended with a newline as the file holds it, until a line starting with `-----END`. The result is the same
  text a pipe hands over. At most 200 lines are read. A paste that ends or runs past the bound without its end line
  throws a `VaultException` naming the lines read and `--from-file`. `call()` prints it and exits 65 before anything
  is stored. Every line read is wiped, and so is the builder on a refusal. The three-argument `readValue` that
  `HostStopSecretsCommand` and the tests call stays as it was.
- **`StoredKey.fromFile`**: when `describe` answers `null`, the key is refused as "cannot be read as an OpenSSH key; it
  is incomplete or damaged" before any algorithm is judged. The "not Ed25519" refusal now names `--from-file <file>`
  instead of `--file`.
- `doc/credentials.md` says that a pasted key is read to its end line and that a paste cut short is refused.
- Tests: `VaultPutCommandTest` has three new tests. A key ssh-keygen writes into the test's temp dir, typed line by
  line, gives the same text and the same stored value as the piped file, and every line is wiped. Armour plus one
  line and then nothing is refused as incomplete after 2 lines. A paste with no end is refused at 200 lines.
  `StoredKeyTest` has two: the armour line alone is unreadable, not "not Ed25519", and an ssh-keygen ECDSA key is
  still "not Ed25519" and names `--from-file`. The value comparisons assert a verdict, so a failure prints no key.
- Not measured here: the acceptance's run in a real terminal on the ubuntu VM. It would check three things. An
  Ed25519 key and an OpenSSH-wrapped RSA key pasted at `porelo vault put` are each stored as `--from-file` stores
  the same file (`unchanged NAME` on a second put by file). Each continuation line is read with no prompt and no
  echo. A paste cut before its end line is refused as incomplete, and the vault file is unchanged.

## Built after the reading (2026-11-13)

- **Echo is off for the whole capture.** `readPassword` turns echo back on before it returns, so paste bytes arriving
  between two reads could reach the scrollback (Codex's reading, derived from the JDK's source, not reproduced).
  `EchoOff` saves the terminal with `stty -g < /dev/tty`, switches echo off before the prompt and gives the saved
  settings back after the last line, on a refusal, and on a signal through a `Teardown` hook - the same `stty` calls
  `TerminalAttach` makes. `readPassword` finds echo off and leaves it so. A terminal that cannot be saved is left alone
  and read as before.
- **`valueFrom` reads through `VaultPutCommand.Typing`**, which `Typing.of(Console)` makes from the console, so a test
  goes through the reads `valueFrom` wires: a further-line reader answering nothing fails it.
- **A paste cut short leaves the read waiting**, as the terminal is still open. The value prompt and
  `doc/credentials.md` say that Ctrl-D refuses it and Ctrl-C quits.
- **The wiping tests assert verdicts**: no line of a key is handed to an assertion.
- **Left as it was:** converting an OpenSSH-wrapped RSA key writes it in plain text to a temporary file for
  `ssh-keygen -p`, shredded afterwards but left behind if the process is killed first. That predates this issue; the
  pasted RSA key inherits it.
- Tests: `EchoOffTest` (echo held and given back, a terminal that cannot be saved left alone, a failed switch given
  back at once, a real SIGINT to a child JVM gives the settings back); `VaultPutCommandTest` has three more, through
  `valueFrom`: a pasted key stores what the pipe stores, echo is off from the prompt to the end line, and Ctrl-D
  refuses a paste and gives the terminal back.
