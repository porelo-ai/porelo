# B209 — A Vault Unlocked From Standard Input

**Status:** Estimated.
**Size:** S - one option on `vault unlock` reading one line from its own standard input, the same in `vault init`, a
refusal when standard input is a terminal, and tests; no change to how the vault is opened.

**What must be true.** `porelo vault unlock --passphrase-stdin` reads the passphrase as one line from its own
standard input, so a program can hand it over an ssh channel from its own memory. Nothing on the host, the person's
drive or the person's computer holds it in a file.

## Why

porelo-frontend is to open the vault of a host it put up, with no terminal on either side. `--passphrase-command` does
not serve: `CommandPassphrase` runs the command with a pipe of porelo's own as its standard input, closed at once, so
`--passphrase-command cat` reads nothing. Measured on 2026-10-18 on `ubuntu26.04` with a throwaway vault:
`printf 'throwaway-pass\n' | porelo vault unlock --passphrase-command cat` answered "No passphrase available, tried:
systemd-creds, passphrase-command, prompt" and exited 70. A FIFO on the host was weighed and not chosen: another
process of the account could open it first. Chosen by the operator on 2026-10-18.

## Plan

1. **`StdinPassphrase`** in `vault`, a `PassphraseSource` over an `InputStream`: it reads bytes up to the first
   `\n` into a byte array of at most 1024, drops one `\r` before it, decodes UTF-8 into a `char[]`, and zeroes the
   bytes. EOF before any byte, or an empty line, answers nothing; a line longer than the cap is refused as "longer
   than 1024 bytes". It reads no further than the line, so what follows on the stream is left unread. Unit tests over
   a `ByteArrayInputStream`: a line, a line ending in `\r\n`, a last line without `\n`, nothing, an empty line, a
   line at the cap and one over it, and UTF-8 beyond ASCII. Seen red first.
2. **`--passphrase-stdin` on `vault unlock`**, read from `System.in`. When it is given it is the only source, never
   one tier among others, since its caller has no terminal to fall back to.
   - Given with `--passphrase-command` or `--systemd-credential`, it is refused with 64, naming both.
   - Standard input a terminal, it is refused with 64: "type it at the prompt: porelo vault unlock". This is found by
     `/proc/self/fd/0` naming a `/dev/pts/` or `/dev/tty` device, because `System.console()` asks about standard
     output too.
   - Nothing read, it exits 70 with "standard input gave no passphrase".
   - Otherwise it is the unlock it is today: proved against the vault, cached, `--for` kept.
3. **The same option on `vault init`**, read once, with no second asking: a program does not mistype, and
   `--passphrase-command` asks once too. The checks of step 2 apply, and so does the empty passphrase's refusal.
4. **Tests of the commands** in `app-vault`. `PoreloContext` gains its standard input (`System.in` in the real one), so
   a test hands a stream: unlock and init with a piped line, each refusal above with its exit code, and the
   passphrase never in the output.
5. **One scenario in `vault-commands.feature`**: `printf '%s\n' <passphrase> | porelo vault unlock --passphrase-stdin`
   unlocks, and a later `vault list` reads the vault. `printf` is a builtin of the shell, so the passphrase is in no
   process's argument list.
6. **Docs:** `doc/credentials.md` gets "a program on another machine" beside the script's `porelo vault unlock`, with
   `ssh HOST porelo vault unlock --passphrase-stdin` and the passphrase written to the channel. `doc/commands.md`
   gets the option, and the changelog says it.

## Acceptance

- `printf 'pass\n' | porelo vault unlock --passphrase-stdin` unlocks a vault whose passphrase is `pass`; the same
  with `--passphrase-command cat` still answers 70, so the measurement stands and the new option is the way.
  Seen red first: the option unknown, 64.
- `porelo vault init --passphrase-stdin` creates a vault from one piped line and caches it.
- At a terminal, `porelo vault unlock --passphrase-stdin` refuses with 64 and names the prompt.
- With `--passphrase-command` beside it, both are named and it exits 64.
- An empty line, or nothing, exits 70 and leaves the vault shut.
- No output of any test holds the passphrase.

## To be checked

- **The cap of 1024 bytes.** A generated passphrase from a password manager is far below it, but its length is a
  guess. Above it, the refusal says the limit.
- **`vault passphrase`** (changing it) has the same gap for the new passphrase. It is left out here, since Frontend's
  need is the unlock; a second issue if it is wanted.
