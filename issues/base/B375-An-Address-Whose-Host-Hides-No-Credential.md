# B375 — An Address Whose Host Hides No Credential

**Status:** Idea.
**Size:** M, proposed - the bracketed host and the `%40` authority refused or cut, the scp form cut where git splits
it, IPv6 hosts kept whole where credentials are matched, the helper run with UTF-8 paths, and one test made to test
what it names, each with its test.

**What must be true.** No spelling of an address carries a credential past [B368](README.md#b368), and an address
without one is kept and matched as git fetches it. A second reading of [B368](README.md#b368) on 2026-10-31 found
six places where this does not hold yet:

- A bracketed host takes `%` followed by any zone, so `ssh://[a:c0ffee%40example.org]/x.git` passes as an IPv6 host
  with a zone; git decodes `%40` and hands ssh `a:c0ffee@example.org`, and the token is kept in the trust record and
  answered. Only `%25<zone>` or a zone git does not decode is a zone, and an IPv6 literal has its colons.
- An `ssh://` authority with a plain user before `@` is kept as given even when `%40` follows it, so
  `ssh://git@bob%3Atok%40example.org/x.git` keeps `tok` in every kept file.
- The scp form is cut at the first `/`, so a password holding `/` (`bob:tok/4711@example.org:x.git`) is kept, and
  `git@example.org:team@v2:repo.git`, which has none, loses its user and host.
- `GitCredentialNames.hostOf`, `CredentialRegistry.normalise` and `RepositoryAddress.key` split a bracketed IPv6 host
  at its first `:`, so a token is never lent to `https://[2001:db8::1]/x.git` and the scp and URL spellings of one
  IPv6 repository are not the same repository.
- `registry joinable` runs git and `porelo vault credential` with `LC_ALL=C`, so a vault path with a non-ASCII
  character cannot be read and the project is answered as unreachable.
- `RegistryJoinableCommandTest`'s netrc test passes without the empty `HOME` git is given: it records the
  environment of the demo fetch only.

Each is answered by a test that fails today.
