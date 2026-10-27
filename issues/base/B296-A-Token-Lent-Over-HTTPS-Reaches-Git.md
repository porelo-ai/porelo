# B296 — A Token Lent Over HTTPS Reaches Git

**Status:** Estimated by the operator on 2026-10-27; accepted on the operator's word the same day.
**Size:** S - one string in `FollowCredential.helper()` and a test that has git itself run what that string names.

**What must be true.** Every token Porelo lends git for an `https://` address - from the vault (`--entry`), a file
(`--file`) or a variable (`--env`) - reaches git: the helper Porelo configures is one git actually runs, so a start's
upstream check, `porelo credentials check`, a follow and the gate's clone, refresh and forward read a private forge
with the declared token.

## Why

Found in the sprint-5 test on the rented host, 2026-10-27, and measured there and here. `porelo task start` of a task
whose repository's upstream is `https://forge.porelo.internal/...` stopped with "forge.porelo.internal refused the
vault's 'git.token.forge.porelo.internal'", although the stored token was valid: the same `git ls-remote` with the
helper called by hand answered.

`FollowCredential.helper()` builds `credential.https://<host>.helper='<porelo>' vault credential --entry <name>`, the
binary's path quoted by `word()` so that a path with spaces stays one word. git reads a helper value three ways: one
starting with `!` is a shell snippet, an absolute path is run as it is, and **anything else is the name of a
`git credential-<name>` command**. A value starting with a quote is the third kind, so git runs
`git credential-'/usr/bin/porelo' vault credential ...`, which is no git command. git then has no token, asks for a
username without a terminal, and fails with "could not read Username ... terminal prompts disabled", which
`UpstreamProbe` sorts as a refused credential. Measured with git 2.53 and a stub helper: `'/path/helper'` fails that
way; `/path/helper` and `!'/path/helper'` answer.

The string has been like this since the first version (`da934c1b`), and no test covers it. It never showed because
every forge reached until now was reached over ssh, where a key is lent through an agent socket instead.

Hit by it: `UpstreamProbe` (a gated start's check, and `porelo credentials check`), the gate's clone, refresh and
forward through `VaultGitCredentials`, and a follow of an `https://` project with a token. A dry run does not run the
start's probe, so it passed where the start refused.

## Plan

1. `FollowCredential.helper()`: `".helper=!" + word(PoreloBinary.path().toString()) + " vault credential " + ...`.
   With the leading `!` git hands the rest to `sh -c` with ` get` appended, and the quoting keeps the path one word.
   The vault entry's name and the username stay quoted as they are.
2. **Red first:** a test that takes the configuration `arguments()` returns for a token credential, with
   `PoreloBinary`'s named path pointing at a stub helper script that answers a fixed `username=`/`password=`, and
   runs `git -c <that> credential fill` for the host. Today git answers nothing (red); with the fix it answers the
   stub's values. A path with a space in it is one of the cases.
3. A second case for `--file` and `--env`, which go through the same `helper()`.

## Acceptance

- `git credential fill` through the configuration `FollowCredential` builds answers the stored token, for `--entry`,
  `--file` and `--env`, and for a Porelo binary whose path contains a space.
- On a host with a token stored for an `https://` forge and no other git configuration, `porelo credentials check
  <url>` says `read`, and `porelo task start` of a task on that forge gets past its upstream check.

## Meanwhile

The sprint-5 host runs with a bridge the operator set: the Porelo account's `~/.gitconfig` names the same helper the
way git runs it, `credential.https://forge.porelo.internal.helper=!/usr/bin/porelo vault credential --entry
git.token.forge.porelo.internal`. git asks the global helper before the one on the command line and stops once it
has a username and password, so the token comes from the same vault entry. Remove it once a snapshot with this fix is
on the host.
