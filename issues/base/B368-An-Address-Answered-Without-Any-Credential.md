# B368 — An Address Answered Without Any Credential

**Status:** Idea.
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
