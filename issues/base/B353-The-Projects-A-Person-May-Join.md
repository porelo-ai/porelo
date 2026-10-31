# B353 — The Projects A Person May Join

**Status:** Review. Backlog (operator, 2026-10-31), sprint 6m.
**Size:** M, proposed - one verb joining the registry's read and the access file's check, with no rule of its own.

**What must be true.** A person's Porelo answers which projects they may join, from the organisation's registry, without
the interface reading a file or checking a signature itself. `porelo registry joinable <checkout> --login L --json`
reads the registry as [B339](README.md#b339) does, and for every project a host's access file names, fetches and checks
that project's access repository as [B342](README.md#b342) does. It answers:

- `projects`: each project whose verified access file admits L, as `{"project", "host", "host_account", "host_card",
  "source", "access_repository", "revision", "signers", "admins"}`. `host_card` holds the host card's accepted values
  (`address`, `ssh_port`, `ssh_host_key`, `homeserver`, `services`), so the one tunnel is opened from the accepted card,
  never from a name alone. `revision` is the verified access commit, and `signers` are the project's trusted signers.
- `refused`: each project whose access repository cannot be read or does not verify, as `{"project", "host",
  "access_repository", "why", "admins"}`. It is never left out, and it never shows as an empty project list.
- `admins` are the verified admins who may grant access, in the registry's contact shape: `login`, `name`, `email`.

A forged, revoked or stale admission is refused, and nothing is followed or written: the verb only reads.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A project whose verified access file admits L is answered under `projects`, with its host card, revision and signers.
- A project whose access file does not name L, or names them `locked`, is not answered under `projects`.
- An access repository that cannot be fetched, or whose access file breaks its signature rule, is answered under
  `refused`, naming its address, its host and its admins.
- A registry that does not verify answers nothing under `projects`.
- Seen to fail first: there is no verb that answers the projects a person may join today.

## Done

`porelo registry joinable CHECKOUT --login L [--json]` reads the registry through `registry read`'s own reader, and
for every project of every accepted host access file checks its access repository with the follow's own code: fetched
as a follow fetches, its head signed by the key the registry holds for the project's admin (the accepted, not refused
card of the `admin` the host's access file names, with the fingerprint that file pins), against the account's
revocation list, its `access.yml` and cards read from that commit as `project verify` reads them. The answer is
`projects` and `refused`. A project is under `projects` only when its verified file admits L, neither locked nor
revoked, with the host among L's `hosts`. Each row carries `host_account` (L), `host_card` (the accepted card exactly
as `registry read` answers it), `homeserver`, `source`, `access_repository`, `revision` (the verified access commit),
`signers` (the keys it was verified with) and `admins` (the file's admitted admins as registry contacts).

The access file gains one optional field, `source`: the project's own repository as `project follow` takes it,
written `ssh://`, `https://` or `user@host:path` and checked by the same address rule as a registry's
`access_repository` (now `Repository.isAddress`, shared by both), never an option or a remote helper's transport;
`project access` keeps it when it rewrites the file. A row's `source` is that field without a credential; a verified
file naming none answers its project under `refused`, "the access file names no project repository", never as a
row. The homeserver has its place in the verified access file, `transports.matrix.homeserver`, answered as the row's
`homeserver` (`null` when the file names none); a host card carries no homeserver.

A host without an accepted card, an admin whose key the registry does not hold, an access repository that cannot be
fetched, a head not signed by that key and a file without `source` are each under `refused` with the address, the
host, why and the registry's contact for the admin. A registry whose history is refused as a whole answers 65 and
nothing on stdout. A credential in an address is never printed. Each repository is fetched into a scratch directory
under the system's temporary directory, removed before the answer; nothing is followed or pinned.

`porelo host whoami [--json]` asks the account's daemon at `$XDG_RUNTIME_DIR/porelod.sock` and answers
`{"login", "uid", "return_socket"}`, or 66 `{"reason": "no-daemon", "words"}` when nothing answers there.

Tests: `RegistryJoinableCommandTest` (an admitted login answered with its card, homeserver, source, revision and
signers; unnamed, locked, revoked and other-host logins not answered; a forged head, an unreachable repository, a host
without a card, an admin's other key and a file without `source` refused, never left out; a credential in an address
or a `source` not printed; no homeserver answered `null`; a refused registry answering 65 and nothing; a login of
another shape refused before anything is read), `AccessFileReaderTest` (`source` read as written; an option, a
helper's transport, a path, `file://`, a space or a non-text value refused), `ProjectAccessCommandTest` (a change
keeps `source`) and `HostWhoamiCommandTest` (the account and the socket a real varlink server answers on; a socket
file nothing answers on is `no-daemon`).

A reading's findings, fixed: of each URL answered (`access_repository`, `source`, `homeserver`) everything between
the scheme and the last `@` is left out but an ssh user, so a token holding an `@` and an ssh password are no longer
printed (`RepositoryAddress.withoutCredential`, which cuts at the first `@` and keeps the address a project is fetched
from, stays as it is for its other callers); `user@host:path` is answered as written. The shared address rule
(`Repository.isAddress`, for a registry's `access_repository` and an access file's `source`) refuses an `@` after a
URL's host: git reads the host before the first slash, so `https://user:to/ken@host/...` reached git with part of
the token as its host and named a `git.token.<part>` vault entry in `why`. `Repository.refuseAsOption` asks the value
as given rather than a stripped copy, so a space, tab, line break or other blank anywhere is refused, where a
trailing one was accepted and kept. A registry that accepted no commit (its founding `owners.yml` unsigned, by
somebody the forge does not confirm as an owner, or undecided) answers 65 with why on stderr and nothing on stdout,
never an empty answer. A host whose `access.yml` a commit changed and the read refused is named under `refused`, with
`project` and `access_repository` `null`, the path, commit and reason as `why`, and the host admins `owners.yml` names
as `admins`. Every command of the check runs in a chosen environment, as the registry's own git does: no `GIT_*`
variable of the caller's, no account or system Git configuration, no replace objects, no prompt; `PATH`,
`SSH_AUTH_SOCK` and the proxy variables pass, beside what the fetch is lent. `Reconcile.fetch` is not changed, so a
follow still runs as before. Tests: `RegistryJoinableCommandTest` (a user part with an `@`, an ssh password, a
homeserver with a user part; a user part with a slash refused without a word of it; a registry that accepted no
commit refused with 65; a refused host access file named; the caller's `GIT_DIR` and a global `insteadOf` without
effect), `AccessFileReaderTest` and `RegistryAccessTest` (a blank anywhere, an `@` after the host).

## Not claimed

- The registry's trust record is written as `registry read` writes it, and the fetch writes what a follow's fetch
  writes: the known-hosts file's directory, the CA bundle git is given, and a lent key's socket in the runtime
  directory, removed after the fetch. Nothing else is written.
- Only the head of an access repository is judged, as a first follow judges it: an older signed commit that a later
  one replaced is neither answered nor refused.
- An access head signed by a project signer other than the admin the registry names is refused: the project file,
  which names the others, is not read here.
