# B173 — An Agent's Sign-In Kept Without A Person

**Status:** Review.
**Size:** S - `vault login` in `app-vault` gains an option that reads the agent's credentials from a directory instead
of running its sign-in, with its tests and `doc/credentials.md`; the agent API is not changed.

**What must be true.** An agent's own sign-in can be kept in the vault as `porelo vault login` keeps it - renewable on
the host from its refresh token - without a person at a terminal: `porelo vault login <agent> --credentials-from
<dir>` reads the credentials the agent's sign-in would have written, from that directory, through the agent's own
extractor, and keeps them exactly as after a sign-in.

## Why

An agent repository's acceptance kit proves a subscription's sign-in and its renewal against a stand-in token
endpoint in CI (decided 2026-10-11 for `porelo-claude-code`'s
[CC24](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc24)). Today a renewable sign-in is
kept only by `vault login`, after the agent's own sign-in ran at a terminal (`AgentLogin`,
`LoginCredentials.put(..., true)`). `vault import --config-dir` reads a directory, but keeps what it finds as the
host's agent's, not renewable (`CredentialImport`, `put(..., false)`): renewing it would rotate a token the agent on
the host still holds. And `vault put` refuses every `grant/` name.

## Plan

- `apps/app-vault/.../VaultLoginCommand.java`: `--credentials-from <dir>`. With it, the agent's sign-in is not run;
  the agent's `CredentialExtractor` reads the directory, and what it finds goes through `LoginCredentials.put(entries,
  key, value, true)` as `AgentLogin` does after a sign-in, so the refresh token is kept as `grant/<key>` and renewed by
  `LoginRenewal`.
- The directory is the kit's own, a copy made for the run: Porelo never treats it as the host agent's, so renewing
  rotates nothing anybody else holds. The command says that it kept a sign-in read from a directory, not one made here.
- The token URL is what the agent's extractor reports - a constant of the agent, never read from the credentials
  file, which a task's sign-in may have written. For a stand-in, `--token-url <url>` beside `--credentials-from`
  replaces it, and only with `http://` on a loopback address: a host user's command line can point the renewal at a
  stand-in on the same machine, nothing else. Any other value is refused (exit 64) before anything is kept.
- Nothing is asked: the option works without a terminal, and the vault opens as for any command without one.
- Red first in `VaultLoginCommandTest`: with `--credentials-from` and a stub agent whose extractor reads a file there,
  the entry and `grant/<key>` are kept, renewable, and no sign-in runs; a directory with nothing in it is refused
  (exit 69) and nothing is kept; `--token-url http://127.0.0.1:<port>/token` is kept as the entry's token URL, and
  `--token-url https://example.com/token` or `--token-url` without `--credentials-from` is refused (exit 64).
- `doc/credentials.md` says the option, for a test against a stand-in, and that a real sign-in still runs the agent's.

## Acceptance

- A scenario with the stub agent keeps a sign-in from a directory, and the broker renews it against a stand-in token
  endpoint on loopback, the renewed access token reaching a task's request.
- Seen to fail first: the unit tests above, against today's `vault login`, which runs the agent's sign-in instead.

**Settled while building:** `--credentials-from` is not refused at a terminal - a developer reproduces a CI run there
- and is never mistaken for a sign-in: it says it kept a sign-in read from a directory and that no sign-in ran.

## To be checked

- The acceptance scenario with the stub agent and a stand-in token endpoint on loopback, renewed by the broker; the
  unit tests hold the command, the scenario is not yet written.
