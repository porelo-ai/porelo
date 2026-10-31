# B368 — An Address Answered Without Any Credential

**Status:** Review.
**Size:** S, proposed - one address rule shared by the readers, one credential cut used by every caller, the sealed
environment's passed variables, and one refusal, each with its test.

**What must be true.** No address Porelo accepts or answers carries a credential, in any spelling git or ssh reads
as one, and the check `porelo registry joinable` ([B353](README.md#b353)) runs still reaches a private repository
the person's follow reaches. A second reading of [B353](README.md#b353) on 2026-10-31 found five places where this
does not hold yet:

- `Repository.isAddress` ends a URL's authority only at `/`, so `?`, `#` or a percent-encoded `@` (`%40`) in it pass:
  git then reads a token as the host and names it in `why`, or the answered address keeps it.
- The `user@host:path` form allows a second `@`, `%` in the user and `@` in the path, and such an address is answered
  as written, credential included.
- `transports.matrix.homeserver` is not checked as an address at all; its cut keeps an ssh user, looks for `@` in the
  whole URL instead of its authority, and keeps a `%40` credential.
- A registry whose founding `owners.yml` was refused but which accepted a person's card afterwards answers 0 with
  output, where it must answer 65 with nothing on stdout.
- The sealed environment of the check drops `HOME`, the XDG variables and `PORELO_VAULT`, and the variable an
  environment credential names, so `porelo vault credential`, which git starts for a private `https://` repository,
  finds no credential or the wrong vault.

The older `withoutCredential`, used by the default project, the work origin, the mirror file, the task target, the
build status and the checkout approval, still cuts at the first `@`, so a token holding an `@` is half answered
there; it takes the same rule.

Each is answered by a test that fails today.

## Done

One address rule and one cut, `AddressForm` in the core, serve every reader. An address a registry's
`access_repository` or an access file's `source` takes (`Repository.isAddress`) has a real host, a name or an IPv6
address in brackets with an optional zone, and no credential: an `https://` address no user part at all, since a token
alone may stand as the user, and an `ssh://` or `user@host:path` one at most a plain ssh user, without `:`, `%` or `@`
and not starting with `-`; a token is the vault's, chosen by the host. An `https://` host ends at `/`, `?` or `#` and
an `ssh://` one at `/`, as git and libcurl read them, and no `@` follows the host. Measured with git 2.53:
`https://tok?x@host/` resolves `tok`, `https://a:b@c@host/` is a bad hostname to libcurl, `ssh://git%3Atok@host/`
hands ssh `git:tok@host` on its command line, `bob:tok@[2001:db8::1]:x` hands it `bob:tok@2001:db8::1`, and
`bob:tok@host:x` connects to `bob`. No whitespace or control character of any script is taken (NBSP, U+2028, ESC), and
a remote helper's `transport::` is named as git reads a scheme, so `git@[::1]:demo.git` is an address. A credential is
refused when the registry is read, so none reaches its trust record or the answers of `registry read`, `registry
verify` and `registry joinable`; a trust record holding one fails its check and the registry is walked again.

What Porelo keeps or answers of an address (`AddressForm.withoutUserinfo`, and through it
`RepositoryAddress.withoutCredential` for the default project, the work origin, the mirror file, the task target, the
build status and the checkout approval) leaves out, of a URL, everything before the first `/` up to the last `@` there
but a plain ssh user, and of `user@host:path` all up to the last `@` before its host's colon when its user is no plain
name; an authority that holds no real host after that is left out whole, and so is one spelling its at sign `%40`. A
leading blank of any script hides no scheme. An address without a credential is kept as given - an IPv6 zone,
`DOMAIN\alice@host:repo`, `ssh://git@host:~alice/repo` and `file://./repo` alike - so what those callers fetch from
does not change.

`registry joinable` refuses a project whose verified access file names a `transports.matrix.homeserver` that is not an
`https://` base URL (the scheme in either case) with a real host and no user part, query, fragment, blank or control
character (`AddressForm.isHttps`), naming the field and nothing of the value: a query could carry a token past the cut,
as `?x=/bob:tok@evil` or `?access_token=`. A registry without an accepted `owners.yml` answers 65 with nothing on
stdout, whatever card was accepted after its refused founding: `RegistryState` holds owners exactly when an
`owners.yml` was accepted.

The check's sealed environment gives git an empty `HOME` in its scratch directory, never the account's: libcurl answers
a forge from `$HOME/.netrc`, and from the password database's home when `HOME` is unset (both measured with git 2.53
against a loopback server), and git reads attributes under it. Of the caller's environment only `PATH`,
`SSH_AUTH_SOCK`, the proxy variables and each variable a `source: env` token credential for git names pass, never one
git, libcurl, ssh, the loader, the shell or the JVM acts on. `FollowCredential` names the account's home, XDG
directories and vault before `porelo vault credential` in the helper git runs, so the helper reads the vault the
command checked whatever environment git runs in. A token in a vault opened only by a passphrase typed for this
command is not lent: git starts the helper as a process of its own, which finds the vault shut (measured: "the vault is
not open"), so the fetch answers `vault_locked` rather than `unreachable`. A `user: $VAR` is read from the machine's
environment, as the token's variable is.

Tests: `AddressFormTest` (each credential spelling refused and cut; a password before a bracketed or a plain host cut;
a homeserver's user part, query and fragment refused; blanks and controls of any script refused and cut around; every
credential-free address kept as given; the IPv6 forms git fetches taken; a homeserver's scheme in either case and a
combining mark taken), `RegistryAccessTest` and `AccessFileReaderTest` (each credential refused),
`RegistryJoinableCommandTest` (an access address with a credential refused before git sees it and kept in no trust
record, a fetch of it pointed at a loopback port nothing listens on; a `source` with a credential refusing its project;
a homeserver with a user part, a query, a fragment, a blank or a control refusing its project; this build's own
`porelo vault credential` answering git from a real vault the keyring opens, over a loopback forge that serves only
that token; an account's `.netrc` lending the check nothing; only a git token's variable reaching the fetch; a vault
opened by a typed passphrase alone answered `vault_locked`), `FollowCredentialHelperTest` (a user named by a variable
read from the machine's environment), `FollowCredentialTest` and `FollowGitTrustTest` (a token lent from a vault the
keyring opens, and answered shut from one a typed passphrase alone opens), `DefaultProjectTest`.
