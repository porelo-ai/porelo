# B339 — The Registry Read And Written By Its Signers

**Status:** Review.
**Size:** M - estimated 90 minutes from the median of M builds: the per-path rule beside the protected-file rule, the
registry verbs, the forge's key list at first read, docs.

**What must be true.** An organisation's registry repository on the central forge holds `owners.yml` - the registry's
owners and the host admins, each by login and key, and an offline backup key where a single owner or host admin needs
one ([B346](README.md#b346)) -, `people/<login>.card`, `hosts/<host>/card` and `hosts/<host>/access.yml`. Write access
to it is wide, so a commit's signer alone proves nothing: Porelo checks every commit along the first parent since the
state it trusted last, path by path, and every new commit against the revocation list as the follow does
([B341](README.md#b341)).

- A card is changed only by its own key, and a change of key also by its predecessor. A card replacing a lost or revoked
  key is accepted only when a registry owner signs it.
- `owners.yml` is changed only by a registry owner it names, and the first one is accepted only when its signer is an
  owner of the forge organisation whose key the forge lists for their account.
- A host's card and access file are changed only by a host admin `owners.yml` names for that host; the card itself is
  also checked as a host card ([B349](README.md#b349)). A host's access file names each project admin by login and key
  fingerprint, so the host can check them without the forge.
- Any other path is ignored and never read. A commit that breaks the rule keeps the last good state and is named with
  its path.

A card filed for a login is trusted at first read only when its key is among the keys the central forge lists for that
login: its authentication keys as the page `<forge>/<login>.keys` lists them, and on GitHub its signing keys as well.
Both answer without a token; Forgejo's API asks a token even for another person's public keys, so the page is read,
not the API. Before the forge was asked, as when the person is offline, the card is `first-seen`; on a forge without a
public key list it stays so until an admin confirms its fingerprint out of band.

`porelo registry read <checkout> --json` answers `{"people": [{"login", "name", "email", "key", "fingerprint", "state":
"trusted|first-seen|refused", "why"}], "owners": [{"login", "fingerprint", "backup"}], "host_admins": {"HOST":
[{"login", "fingerprint", "backup"}]}, "warnings": [], "hosts": [{"host", "card", "admins", "projects": [{"name",
"admin", "access_repository"}]}]}`: `host_admins` are the admins `owners.yml` names for each host, who sign its files; a
host's `admins` are its access file's project admins. `porelo registry file card|host-card|host-access|host-card-remove
...` writes and signs the commit with the person's key, by plain Git, never through a forge's contents API.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A card changed by another key than its own, or a host's access file changed by another key than a host admin
  `owners.yml` names for it, keeps the last good state and names commit and path.
- A card replacing a revoked key is accepted signed by a registry owner and refused signed by anybody else.
- An `owners.yml` whose signer is not an owner of the forge organisation is refused at first read.
- A card whose key the forge does not list for its login is `refused` with why; one the forge lists is `trusted`; one
  not yet checked is `first-seen`.
- `registry file` makes a signed commit that `registry read` answers as trusted.
- Seen to fail first: there is no registry reader today.

## Done

- Every first-parent commit since the trusted state is judged path by path against the state before it; a refused
  path keeps its last good state and is named with commit and path, and the walk goes on. Removing a path that holds
  nothing accepted changes nothing and accepts nothing; a card's removal is judged against the card last accepted for
  it, by its key or a current owner's, never against what the commit before held.
- The first `owners.yml` is accepted only on a plain yes: its signer owns the forge organisation and the forge lists
  that key. A no refuses it; when the forge cannot say, as without a token, the read stops before that commit with a
  warning to read again with a forge token, and the state and the trust record end at the commit before, so the next
  read judges it again. An accepted `owners.yml` cannot be removed.
- A first card, and an owner's replacement of a card, is `trusted` when the forge lists its key, `refused` when the
  forge lists all its keys and not this one, `first-seen` when it could not be asked or gave its list only in part (on
  GitHub the `.keys` page without the signing keys). A `first-seen` card is asked again at every read, and counts as
  listed when the forge lists any key of the chain it was accepted along (its key or a predecessor that handed it on);
  settling to `refused` is warned about. A refused card is answered and warned about but is no card and accepts nothing:
  its blob is not the path's accepted one, it makes no commit accepted and leaves the path's earlier refusals in force,
  and the next card for the login, or an owner's, is judged as a first one. A refused card is asked again at every read
  too: once the forge lists its key it is `trusted` and its blob accepted, with a warning. A `first-seen` card holds its
  login only until the login's own card comes, signed by a key the forge lists: that card is judged as a first card and
  replaces it; when the forge cannot say whether it lists that key, the read stops before that commit with an
  `undecided` warning, as for an undecided founding. A key the `.keys` page lists is `trusted` without asking GitHub's
  signing-key API, which is asked only for a key the page does not list, so a rate limit others spend cannot leave such
  a card unsettled.
- A revoked key is never trusted: a card whose key, or any key it was handed on from, or the key it names as its
  predecessor, is revoked is `refused` with why "revoked key", when it is judged and when it is settled, an owner's
  replacement among them. The commit that revokes a key refuses at once every card held with it or handed on from
  it, under any login, `trusted` ones among them, warned about by that commit. A card's chain
  holds each key once and at most 16 keys, its own among them; a change of key that would hand on a 17th is refused
  ("too many keys handed on"), and settling asks the forge once per distinct key. Settling keeps the state as a walk
  would: a card settled to `trusted` lifts its path's refusals from its own commit and before, never a later
  commit's; one settled to `refused` leaves the registry accepted while anything was accepted that rests on no
  `first-seen` card (kept in the trust record as `firm`), or another `first-seen` card is held. The trust record
  keeps, for each accepted file and for no other path, the commit it was accepted from and that commit's signer
  (`changes`), and for each card, refused ones too, the commit it was read from (`change`); settling a card to
  `trusted` adds its change. A card's predecessors are only the keys it was rotated from, each an accepted card's
  key at its path: a first card, or an owner's replacement, has none whatever predecessor it names, so settling
  never trusts a person through a key the person never had accepted. A trusted card whose key or predecessor is
  revoked is refused when settled too.
- The trust record is checked whole when read: `changes` has exactly the paths of `blobs`, a card's change is its
  path's, a host card's change is signed by the admin who signed the card; owners, hosts and `trusted` cards come
  with `firm`, a `first-seen` card with `accepted`, and `firm` only with `accepted`. Against the history, every
  change, card and refusal names a commit on the first-parent line of the record's commit, and every change and
  card's commit touched its path against its first parent, is verifiably signed by the signer named and holds the
  blob named. The owners, host admins, people and hosts the record names must be exactly what those blobs say,
  each parsed again; a card not refused may hold no revoked key or predecessor. Anything else refuses the record
  whole. Every object name in a record is checked hex before git sees it, and every git the read runs on a
  revision ends its options first (`--end-of-options`). Every state a walk or a settling leaves is read back as it
  was.
- The forge answers "cannot say" for whatever a token may not see. On GitHub the token's own membership is asked
  first: when its account is an active admin of the organisation, a 404 for the login's membership is a plain no,
  otherwise no answer. On Forgejo the login's permissions in the organisation answer (`is_owner`), which every Forgejo
  serves, so the `Owners` team is no longer searched; a 404, 403 or anything else is no answer. A login the forge does
  not know lists no keys, without asking the API. The forge is asked by a client that follows no redirects.
- The read takes exactly the commit the checkout has at `HEAD`, on a branch or detached. The trust record is checked
  as the walk checks: host cards as host cards, projects as the access file, logins once, and every person, owner and
  host with its file; a record that names owners, or people not refused, or hosts while nothing was accepted, or hosts
  or host admins without owners, is refused whole. Every diagnostic, a host card's among them, replaces control,
  format and separator characters, C1 and bidi controls among them.
- `registry read` and `registry file` run every git on the checkout in a chosen environment (no `GIT_*` of the
  caller's, no account or system configuration) with `core.hooksPath=/dev/null` and `core.fsmonitor=false`. An
  `origin` with more than one `url`, from an include too, or one git rewrites to another, is refused (64). An origin
  that names a port gets no token unless one is declared for that host and port. The agent is the one the machine's
  environment names, never one the process inherited otherwise.
- `registry file` takes who may sign from the registry as `registry read` accepts it, with its trust record, never
  from the checkout's `owners.yml`. It commits by plumbing, never through the checkout's index or work tree: the
  bytes become a blob as they are (`hash-object --no-filters`), in a scratch index on `HEAD`, `commit-tree -S` signs
  through the agent, and `HEAD` moves only from the commit it was read at; author and committer are the checkout's
  `user.name` and `user.email` (64 when unset). It writes onto the commit the read judged, its parent and the old
  value `update-ref` checks: a `HEAD` that moved since, or a read that stopped before `HEAD`, is refused (65) naming
  both commits, checked first, before the admins or the agent are looked at. The work tree gets the file only once
  `update-ref` moved `HEAD`; a refusal or a `HEAD` moved meanwhile leaves it untouched. A revoked key is never
  chosen to sign, a card of a revoked key or handed on from one never filed. The checkout's index then follows `HEAD`
  for that path with every filter driver left without a command. When `HEAD` holds the very bytes at that path in a
  commit the rule refused (its blob is not the one the read accepted), the path's removal is committed first, then
  the file, both signed by the filing key, but only where nothing was accepted for the path or for a host's files:
  for a person's card the registry accepted another card for, it is refused (65) naming the refused commit, to be
  reverted with git, since a removal would take the accepted card and its chain with it. "Filed already" is answered
  only for a blob the read accepted.
- `registry file` takes `--id ID` (`[A-Za-z0-9._-]{1,64}`) and `--base COMMIT`, both optional. `--base` is the full
  commit the change was made against; another `HEAD` is refused (65) naming both. `--id` goes into the commit as a
  `Porelo-Change-Id` trailer beside `Porelo-Change-Digest`, the SHA-256 of the path, a NUL and the bytes. Asked
  again, it walks the last 1000 first parents (`rev-list --first-parent`) and reads each commit as Git stores it,
  as bytes (`cat-file --batch`, cut by the byte sizes Git gives, decoded only once cut out; a commit that is no
  UTF-8 has no signature here), never through `log` or `verify-commit`, so no program the checkout's configuration
  names starts. Every object name handed back to git is checked to be 40 or 64 lower-case hex, and every git that
  takes a revision gets `--end-of-options` before it. A
  trailer counts only as written, once, with nothing trimmed but the message's final line feed; a commit with two of
  either is no match. Every commit carrying the id and a digest is this id's: one with another digest refuses the
  id (65) before `--base` is looked at; each other must be the change the read accepted for the path (its last
  change in the trust record), its first-parent diff that path alone, its tree holding the bytes filed (none for a
  removal), its `gpgsig` an SSH signature that verifies here, namespace `git`, against the key the read took as that
  commit's signer; a removal, which leaves no accepted file, must be the last commit on the line that changed the
  path, removing it, verifiably signed by a key that may remove it now, while the registry accepts none there. One
  that is not, unsigned or refused, is refused (65) by name and never passed over. The one made on the same base is
  answered (exit 0, nothing written); made on another, the id is refused (65) naming both, so an id once carried by
  an accepted commit is never committed again, wherever `HEAD` or `--base` stand. The removal committed before a
  filing again, and the card put back before a removal, carry only `Porelo-Change-Step: ID`, so they never answer
  for the id; one is stepped over to find the base only when the change's own signer signed it and it changed that
  path alone. `registry file host-card-remove CHECKOUT --host HOST` removes the `hosts/HOST/card`
  the registry accepts, signed by a host admin `owners.yml` names for HOST, whatever `HEAD` holds there: when `HEAD`
  holds none, the accepted card is put back by a first commit and then removed. With no card accepted it is refused
  (65), saying so when `HEAD` holds one the registry refused, which this verb does not remove.
- A card keeps the predecessor it names apart from the keys its person held: `predecessors` holds only keys handed on
  by a change of key, and `predecessor` the fingerprint the card names, whichever way it was accepted. A card whose
  key, any key it was handed on from, or named predecessor is revoked is refused ("revoked key"), at the commit that
  revokes it, at every later read, and by settling, which never makes it trusted however the forge lists its key; a
  record holding one trusted is refused as malformed.
- One judge reads a card for the walk and for the trust record's check: a person's card readable, its login the
  path's, signed by its own key and any predecessor it names, placed by its own key or a registry owner (for the
  record: an owner it names); a host's card readable, naming the path's host, signed by the commit's signer. The
  check of a record starts with the same whole-record validation `fromJson` runs (each id, fingerprint and path as a
  walk writes it, the blobs exactly the files read, the changes exactly theirs), whether the state came from its file
  or was handed over in memory; a state without a commit is checked too.
- A change of key from a `first-seen` card is judged by its chain as settling judges it, the new key first, then the
  keys it was handed on from: a fresh read and a read from its own record answer the same.
- Whether a registry counts as accepted (`revision` not `null`) follows from one rule, used by the walk, by settling
  and by the record's check: a path accepted firmly, a `first-seen` card held, or a `first-seen` card's removal
  accepted while nothing was firm. Such a removal is kept in the record (`removed`: login, key, the keys it was
  handed on from) until something is firm, and at the end of every read, fresh or from a record, the forge is asked
  about it as about the card: listed makes the registry firm, others listed takes the removal back. A record whose `accepted` is not what the rule says is refused
  as malformed. A fresh read and a read from the record of any earlier read answer the same revision, people and
  blobs.
- Operator's decision, 2026-10-31: a trust record that fails any check, or does not read, is discarded, said so in one
  warning naming why (`the registry trust record was discarded and the registry read afresh from its first commit:
  ...`), and the registry walked again from its founding commit; the fresh state replaces the record, and a fresh walk
  that stops before its first commit deletes it. Slower, never weaker. Every command that reads the registry (`registry
  read`, `registry file`) prints the read's warnings on stderr, `porelo: warning: ...`, through the printable filter, so
  a discard is never lost. A head that does not continue the record's commit still refuses the read (65): that is the
  history, not the record. The record in `$XDG_STATE_HOME` is checked, not hardened against whoever writes that
  directory, who can change Porelo's configuration and binaries too: an accepted risk in [the
  decisions](../../doc/decisions.md#a-registrys-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory).

- Not claimed: a reader with no forge token, or with one that cannot see the organisation, leaves a founding commit
  undecided, and the read stops there until such a token reads; the same holds for a login's own card over a
  `first-seen` one, so an offline read of such a registry stops there too. A checkout inside a parent repository or
  behind linked ancestors (a symlinked parent, `.git` or `gitdir:`) is read as git finds it. Not claimed by a test: that
  an inherited `GIT_DIR` or `GIT_CONFIG_COUNT` does not reach the checkout's git, and that `--no-filters` matters, which
  `hash-object --stdin` already implies. An `--id` commit is answered only among the last 1000 first parents, and only
  while it is the path's last change the registry accepted: once another change of the path is accepted, asking again by
  that id is refused (65), to be filed under another id. Anybody who can push a commit carrying the id refuses the id
  for its owner, whatever the digest. A trust record written before `changes`, `firm` and `predecessor` existed is
  discarded, and the registry read afresh once. `revoked` cannot be parsed from any file: a record is checked only not
  to trust a revoked key, not that its list of revoked keys is the history's. Nor is `removed` checked against the
  history: an entry only keeps the registry accepted until the forge answers about it. The record's blobs are read from
  the commit each was accepted from, not from the record commit's tree, which may hold a later refused version of the
  path. `RegistryVerifier`, the older reader, is not changed. A changed file's signer is checked against the record for
  a host card (its card's signer) and a person's card (its own key or an owner the record names); for an access file or
  `owners.yml` the record keeps no history of who could sign then, so only the commit's own verified signature binds it.
  A card an owner placed whose key owns the registry no more, the owner's key rotated or the owner removed in
  `owners.yml` after the card was placed, fails the record's check, so each read of that registry walks it afresh until
  the person files their own card: slower, not weaker. These checks, which need who could sign at the time, are
  [B359](README.md#b359); further ways a hand-edited record could pass are Ideas, not fixed here. A removal asked again
  by its id is answered from the history, not the record: one of a card nobody accepted, signed by a key that may remove
  it now, is answered too.