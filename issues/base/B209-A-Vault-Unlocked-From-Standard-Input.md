# B209 — A Vault Unlocked From Standard Input

**Status:** Idea.
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
