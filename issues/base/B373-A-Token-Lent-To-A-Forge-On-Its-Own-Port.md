# B373 — A Token Lent To A Forge On Its Own Port

**Status:** Backlog.
**Size:** S - one module pair (`app-base`, `app-model`) and their tests, no contract other repositories read; the
helper's key is built from the address git is given, and the vault entry names stay as they are.

**What must be true.** A vault token declared for an `https://` repository on a port other than 443 is lent to git
for that repository. Today `FollowCredential` keys its credential helper by `GitCredentialNames.hostOf`, which ends
the host at the first `:`, so git is given `credential.https://<host>.helper` and, as measured with git 2.53, does
not match it against `https://<host>:<port>/`: the fetch goes out without the token and is answered as unreachable.
The loopback tests do not see it, because they rewrite the address they fetch.

- The helper's key names the port when the repository's address names one.
- A test fetches from a loopback forge on its own port without rewriting the address.

Found by a reading of the address check for credentials on 2026-10-31.

## Plan

Read in the code on 2026-11-02, and measured with git 2.53 (`git credential fill` against a helper keyed several
ways): git matches `credential.<scheme>://<host>[:<port>].helper` against the whole authority of the address it
uses. A key without the port misses an address with a port (other than the scheme's default), a key with the port
misses an address without one, and a key written `https://` misses an `http://` address.

What holds today: `FollowCredential.helper` (`apps/app-model/.../FollowCredential.java:414-444`) writes
`credential.https://` plus `GitCredentialNames.hostOf(credential.match())` plus `.helper`. `hostOf` ends at the first
`:` (`GitCredentialNames.java:80-110`). The helper is given the credential only, not the address that is fetched, and
a declared record for `https://forge.example` also covers `https://forge.example:8443/...` (`Credential.covers`,
boundary `:`), so the record is found and the key then misses.

1. **`GitCredentialNames`** gets one new method, `helperScopeOf(url)`: the scheme, the host and the port exactly as the
   address writes them, without a user, path, query or fragment (`https://forge.example:8443`,
   `https://[::1]:8443`); `null` for an address that names no host. `hostOf`, `candidatesFor`, `impliedName`,
   `storeCommandFor` and `isOne` are not touched, so `git.token.<host>` still names a host and never a port, and
   every other caller of `hostOf` (`RepositoryAddress.key`, `HostKeys.nameOf`, `ForgeIdentity`, `Reconcile`,
   `FollowCredential.impliedKeyNote`) reads what it read.
2. **`FollowCredential`**: `url` goes from `credentialFor` (lines 220-267) through `lend` (277), `fromFile` (297),
   `fromEnvironment` (315) and `fromVault` (330) into `helper` (414), which writes
   `"credential." + GitCredentialNames.helperScopeOf(url) + ".helper=!..."` and no longer reads `credential.match()`
   for the key. The address fetched, not the record's match, decides, so a record for the host and a fetch on its
   port agree. A token is lent over `https://` only: the method returns no scope for `http://`, so nothing is lent there,
   as today, although `kindOf` counts `http://` as a token address.
3. **Tests, red first**, each seen to fail before the change:
   - `FollowCredentialHelperTest` (`apps/app/src/test/.../FollowCredentialHelperTest.java`, beside
     `gitRunsTheHelperAndGetsItsToken`, 37-85): a parameterized case that opens a lease for `https://forge.example.org:8443/team/repo.git`
     (once with a record whose match has no port, once with one whose match has the port, once with no record and a
     vault entry `git.token.forge.example.org`) and asks real `git credential fill` for `host=forge.example.org:8443`;
     it must answer the token. Red today: git says it could not read the username. Also an address on `:443` and one
     with no port still get the token (the default-port rows of the table above), and an IPv6 host with a port.
   - `GitCredentialNamesTest`: `helperScopeOf` for the shapes above; and `hostOf` and `candidatesFor` unchanged for
     `https://forge.example:8443/x.git` (`git.token.forge.example`, `git.token`), which pins that entry names keep
     their meaning.
   - `RegistryJoinableCommandTest` (a loopback forge exists at line 789ff): a fetch from the forge on its own port with
     the address NOT rewritten, so the argument that git sees is the one Porelo built. This needs the test's runner
     (`context()`, 77-112) to leave the `-c credential...` key alone, as it does for any argument not in `rewritten`;
     over TLS.
4. `doc/credentials.md`: one sentence that a token is lent to the scheme, host and port of the address, and that the
   entry's name names the host only. `changelog/unreleased/` entry.

## Acceptance

- `git credential fill` for `host=<host>:<port>`, run by a test against the lease `FollowCredential` opens for an
  `https://<host>:<port>/...` address, answers the token; seen to fail first with git's "could not read Username".
  The same lease still answers for an address without a port and for `:443`.
- The vault entry for such a forge is `git.token.<host>` as before: a test stores that name and sees it lent for the
  address with a port, and sees `GitCredentialNames.candidatesFor` and `impliedName` unchanged for an address with a
  port (no entry name carries a port).
- A fetch of a loopback forge on its own port, with the address not rewritten by the test's runner, reaches the forge
  with the token (the forge sees it); seen to fail first.
- A token is not lent to another port of the same host than the one the record or the fetch names: a lease for
  `https://forge.example:8443/...` answers nothing for `host=forge.example:9443`.

## To be checked

- How a loopback test reaches `https://` without a process-free way to make a certificate: whether `keytool` or `openssl`
  may be run by the test (one real process, about a second).
- `RepositoryAddress.key` (`app-model/.../RepositoryAddress.java:72-90`) takes `hostOf` and drops the port, so two forges
  on one host with different ports share a key there; whether any caller can be fooled by it is not read yet and is not
  part of this issue unless it is.
- Whether the 9 non-test callers of `FollowCredential.open` all fetch the same address they open the lease for (the
  plan assumes they do; `TaskLaunch.java:1136-1145` and `UpstreamProbe.java:274-301` are the ones to read first).
- `hostOf` keeps its meaning for every other caller, one that names a vault entry for a lent token among them.
