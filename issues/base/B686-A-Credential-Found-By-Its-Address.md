# B686 — A Credential Found By Its Address

```json
{
  "$schema": "issue-1.json",
  "id": "B686",
  "title": "A Credential Found By Its Address",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 40,
  "blockedBy": [],
  "covers": "`porelo vault credential --for <url>` answers git's credential request for an https repository with the token Porelo would lend for that address itself, found in the account's declarations or by the names the address implies, so a program that reads a private repository with Porelo's login passes only the address.",
  "openQuestions": 0
}
```

**Why this size.** one option on `vault credential` that reaches the resolution Porelo already uses for its own git
calls, its refusals, and tests; no new state.

Filed 2026-11-12 from the agreed contract for the planner reading the project's private repositories with Porelo's
login.

**What must be true.** A program that the window starts reads a private repository with the account's own login,
and it never sees where that login is kept. It runs git with `-c credential.<url>.helper=!<porelo>
vault credential --for <url>`, where `<porelo>` is the absolute path the window hands it. Porelo finds the
credential for that address by the same rule it uses for its own git calls, and answers git's `get` on git's pipe.
The program gets no entry name, no map from addresses to entries, and never a bare token on its standard output.

**Why.** Today `vault credential` takes only `--entry`, `--env` or `--file`, so a caller must already know the vault
entry. The mapping from address to entry lives inside Porelo (`FollowCredential.credentialFor`): first a credential
the account declared for the address (`CredentialRegistry.forUrl`), then the names a git address implies
(`GitCredentialNames.candidatesFor`), whichever the vault holds first. Handing the planner a copy of that mapping
would make two copies of it.

## Plan

- `VaultCredentialCommand`: `--for <url>`, exclusive with `--entry`, `--env` and `--file`. It resolves through the
  same rule as `FollowCredential.credentialFor`, which is made reachable for a token without lending a socket. It
  answers only a `get` whose `protocol` and `host` (and port) are the address's own, compared exactly as the
  organization's forge is (ASCII, lowercased); a lookalike or another host gets nothing.
  The request's path must equal the address's path or lie below it at a `/`; the port is the effective one (443
  when none is named). The token never reaches stderr, a log or a file: it goes only to git on standard output.
- An https address with a token only. An ssh address, or one whose credential is a key, is answered with nothing,
  because its key goes through the agent, not through this helper.
- A shut vault ends the command with a line on stderr and git's own failure, never a prompt: the caller is not at a
  terminal.
- `store` and `erase` are ignored as they are for `--entry`.
- Red first: `--for` with a declared credential answers its token; with only an implied name, that one; for another
  host in git's request, nothing; for an ssh address, nothing; with the vault shut, the stderr line.
- `doc/commands.md` and `doc/credentials.md` state the option and its limits.

## Acceptance

- git run with `vault credential --for <url>` as its helper reads a private https repository whose token is in the
  account's vault, and the token never appears in the caller's environment, arguments or output.
- A request for another host, an ssh address, or a shut vault gets no token; a test fails while one does.
- A request for another port, or a path beside `--for`'s (`/acme/p2` for `/acme/p`), gets no token; the token is
  in no stderr line of any of these runs.
- Agreed with the planner for PL87 on 2026-11-13.

## Built

- `FollowCredential` (app-model): the choice of a credential for an address is one private rule, `chosen` - the one
  declared for the address, else the first implied name the vault holds. `credentialFor` uses it as before. New
  `tokenFor(context, url)` answers a sealed `Token` (`Found` with the declared user, `Shut`, `None`) by the same rule,
  lending nothing, opening no socket and never prompting; it is `None` for an address that is not https or names more
  than one host, and for an ssh key or an agent's credential.
- `porelo vault credential --for <url>` (app-vault): exclusive with `--entry`, `--env` and `--file` (exit 2). git's
  request is kept field by field, a field sent twice dropped. It answers only for https, an ASCII host equal once
  lowercased, the effective port (443 when none is named) and a path equal to `--for`'s or below it at a `/`; an
  empty, `.` or `..` segment is refused, and a `--for` without a path takes any path. Anything else, or nothing held:
  one stderr line, exit 0, no output. A shut vault: one stderr line, exit 1, no prompt. No stderr line names the
  entry, the file, the variable or the address.
- git sends a helper the path only with `credential.useHttpPath=true`; the caller passes it with
  `-c credential.useHttpPath=true`, and a `--for` with a path answers nothing without it. Where the helper answers
  nothing git falls through to its own terminal prompt, so the caller also sets `GIT_TERMINAL_PROMPT=0`.
  doc/credentials.md says so in "A program reading a private repository", with the limits: a `--for` without a
  path serves every repository on that host and port, one without `.git` does not serve a `.git` path, and dot or
  empty segments, non-ASCII hosts and repeated fields get nothing; doc/commands.md names the option. Changelog:
  credential-for-address.yml.
- git's request is read to 64 KiB and 256 lines at most; past either, one stderr line, exit 0, nothing answered. An
  http, non-ASCII or otherwise unserved `--for` address each has its own stderr words; only an ssh address speaks of
  the agent.
- Red first: `VaultCredentialForTest` (53 cases, each point above) and
  `TokenFetchOnItsOwnPortTest.aProgramReadsAPrivateRepositoryByItsAddressAlone`, where real git fetches over TLS from
  a loopback forge with only the helper, the address and useHttpPath - for a declared file and an implied vault
  entry; a `--for` naming another path brings no token to the forge.
