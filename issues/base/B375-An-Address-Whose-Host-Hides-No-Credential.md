# B375 — An Address Whose Host Hides No Credential

**Status:** Review.
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

## Done

Each of the six holds, each measured with git 2.53 and an ssh stand-in that prints what git hands it.

A bracketed host is an IPv6 address with its colons, and its zone starts only at `%25` or at a `%` that two hex
digits do not follow (`AddressForm`): git decodes every other `%` and two hex digits in an `ssh://` host and drops
the brackets, so `ssh://[a:c0ffee%40example.org]/x.git` reached ssh as `a:c0ffee@example.org`. Such an address is
refused as an access repository, a `source` and a homeserver, and `[fe80::1%eth0]` and `[fe80::1%25eth0]` are still
taken. What Porelo keeps of an `ssh://` or `https://` address leaves out its authority whole when `%40` follows its
last `@`, as it did when there was none: `ssh://git@bob%3Atok%40example.org/x.git` reached ssh as
`git@bob:tok@example.org`, user `git@bob:tok`.

The scp form is cut where git splits it, at the first colon after its host, which may be bracketed after `@[`, and no
longer at the first `/`: a user that is no plain name before that colon is left out, and so, when no user stands
there, all up to the last `@` that a host and a colon follow, so `bob:tok/4711@example.org:x.git` (git connects to
`bob`) is kept as `example.org:x.git` and `bob:tok/4711@[2001:db8::1]:x.git` as `[2001:db8::1]:x.git`.
`git@example.org:team@v2:repo.git`, which git fetches from `git@example.org`, is kept as given.

`GitCredentialNames.hostOf`, `CredentialRegistry.normalise` and `RepositoryAddress.key` read a bracketed host to its
closing bracket: git matches `credential.https://[2001:db8::1].helper` against `https://[2001:db8::1]/x.git` and
never `credential.https://[2001.helper`, under which every host of `2001::/16` shared one entry;
`git@[2001:db8::1]:x` normalises to `ssh://[2001:db8::1]/x`, and its two spellings are one repository.

`registry joinable` runs git and `porelo vault credential` in the `C.UTF-8` locale: in the C locale Java reads a
variable and an argument as ASCII, and a vault under a directory with an umlaut failed with `InvalidPathException`,
answered `unreachable`.

`RegistryJoinableCommandTest`'s netrc test also holds the fetch's environment to a `HOME` that is neither the
account's nor this machine's: without the scratch home it passed, since libcurl then reads the password database's
home, which the test cannot plant.

Tests: `AddressFormTest` (a bracketed host that is no IPv6 address or spells `%40` refused, as a repository and a
homeserver; an authority spelling its at sign after a plain user left out whole; a password holding `/` or `@` cut in
the scp form; an scp path holding `@` and a bare bracketed host kept), `CredentialTest` (a record for an IPv6 host
covers its scp spelling and no other host), `GitCredentialNamesTest` (an IPv6 host read whole in every form),
`DefaultProjectTest` (one IPv6 repository in either spelling, two IPv6 hosts two repositories),
`RegistryJoinableCommandTest` (a vault under a path with an umlaut lending its token over a loopback forge; the netrc
test holding the fetch's home).
