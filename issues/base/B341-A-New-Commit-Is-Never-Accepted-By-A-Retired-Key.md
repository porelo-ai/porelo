# B341 — A New Commit Is Never Accepted By A Retired Key

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** L (operator, 2026-10-31) - grew from M (estimated 90 minutes from the median of M builds: the pinned signers
file keeps retired keys apart from those in force, across `app-base` and `app-project`, and the checker takes a
revocation list; grew from S when planned) with its readings beside [B335](README.md#b335)'s.

**What must be true.** A key that leaves `signers` is kept in Git's allowed signers with `valid-before`, so the commits
it signed while it was in force still verify when history is read. Git checks `valid-before` against the commit's time,
which its signer sets: a retired or stolen key could sign a new commit dated before it. The follow therefore checks
every new commit against the signers in force without any key that carries `valid-before`, and against the revocation
list (`gpg.ssh.revocationFile`). `valid-before` serves only for reading history, never for accepting a new commit.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Plan

1. `SignerHandover` (`pin`, `pinned`, `walk`, `Handed`): a key leaving `signers` stays in the pinned file as
   `<project> valid-before=<time of the handover commit> ssh-ed25519 ...`; retirements are kept even when several
   handovers in one fetch leave the set in force unchanged. `PinnedSigners` and `SignerHandover` read the options
   instead of taking fields by position.
2. `ConfigurationGate.verify` accepts a new commit only against a signers file holding the keys in force - every line
   without `valid-before` - and passes the revocation list as `gpg.ssh.revocationFile`; reading history uses the full
   file. A configured list that cannot be read refuses.
3. `Reconcile`, `FollowSignedBy` and `ProjectVerifyCommand` take the list: the follow from
   `~/.config/porelo/revoked.krl` when it exists, `verify` from `--krl FILE`, since it runs without a home.
   `signed_by` and `signers` in the answers stay the keys in force only.
4. Tests, red first: `ReconcileTest` - after a handover, an old commit signed by the retired key no longer verifies
   when history is read (today `pin` drops the key); then a back-dated new commit by it refused, a revoked key in
   force refused, another project's lines untouched; `ConfigurationGateTest`, `AccessReconcileTest` (its "old key
   absent" assertion changes), `ProjectVerifyCommandTest`.
5. Docs: `doc/project-file.md`, `doc/hosts.md` (`verify --krl`), `doc/commands.md`; `project-follow-signed.feature`.

## To be checked

- Which time `valid-before` takes at its boundary in Git's check (committer time, equal times, skew).
- That an applied handover signed by a key retired since stays `UNCHANGED` at the next follow.

## Acceptance

- An old commit signed by a retired key verifies when history is read.
- A new commit signed by that retired key and dated before its `valid-before` is refused and named.
- A new commit signed by a key in the revocation list is refused and named.
- Seen to fail first: today a retired key is dropped from the pinned list, so its old commits no longer verify.

## Done

- Retired keys remain in the historical trust file with their handover cutoff, while only active keys and the
  configured revocation policy can authorize new protected changes, including backdated commits.
- A declaration change leaves exactly its new list active and retires every omitted key, including its signer,
  out-of-band pins and keys supplied on the same request. Unchanged declarations preserve extra pins; a declared
  list, empty included, cannot be removed.
- First follows compare the tip with its first parent's declaration. A parent declaring a list must name the
  operator-pinned signer of the tip; a parentless tip begins with none. Accepted rewrites retain the declaration
  previously in force, and an unreadable recorded baseline refuses with that commit named.
- Complete history is required before judging authority. Raw commit parents cannot be hidden by shallow metadata,
  and missing parent objects refuse. Switching away from unverified mode starts verification afresh for the project
  and its access repository, with no unchecked baseline carried forward.
- Every request checks a prepared snapshot before publishing either clone, signer changes, followed state or an
  event. A refusal requires no restoration and preserves the shared file exactly, including other projects'
  lines and blank lines. Retirement times and staged OpenSSH options are validated before publication.
- Fingerprint matching retains evidence from all verified protected project commits, so a key that signed an earlier
  handover can match after retirement. An unmatched fingerprint refuses, a document-only range supplies no match,
  and every refused request or preview answers no pinned keys, with one whole key or several.
- Signer identity comes only from the commit's own continued signature header, before its message. A signed tag
  embedded in a merge and signature text in another header or the message cannot name or pin the commit's signer.
- Shallow verification is refused before a fetch error can obscure the history guard, and an unsupported local
  file authority is reported as unreadable with its URL named. A shallow source is named before the shallow clone it
  makes, and an unverified follow refuses a shallow live clone, too.
- Not covered by a test of its own: in `project verify`, the scratch clone's shallow guard. A local path is refused by
  the source's guard first, and Git 2.53 never makes the scratch clone shallow from a `file:` URL; the guard stays
  for older protocols.
- A project file whose signers do not parse is named by its file, line and column only: no excerpt of it reaches a
  diagnostic, the same as `project verify` without `--project`.
- Tests: `ProjectVerifyCommandTest` (49), `ProjectReaderTest` (34), `FollowedProjectsAccessTest` (10),
  `ProjectFollowingCommandTest` (3) and the retired-key cases of `ReconcileTest`; the whole suite 4193 green.
