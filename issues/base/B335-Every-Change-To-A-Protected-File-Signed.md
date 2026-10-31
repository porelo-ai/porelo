# B335 — Every Change To A Protected File Signed

**Status:** Review.
**Size:** L (operator, 2026-10-31) - grew from M (estimated 90 minutes from the median of M builds: the follow's
check moves from the tip to each protecting commit, on both repositories, with its refusal, docs and an acceptance
step) when its readings reached the live clone, shallow clones and the published copy.

**What must be true.** A machine following a project applies a new `main` when every commit that changes a protected
file is signed by a key in `signers` as the list stood before that commit; any other commit needs no signature. A
developer changes the rest of the project's repository - plans, issues, documents - on their own, and their pull
request, merged by fast-forward on the host's forge, is applied without anybody signing on top of it.

The check, replacing today's check of the fetched tip alone:

- From the new tip back to the commit in force, along the first parent, each commit is compared with its first parent.
- A commit that changes, adds, deletes or renames a protected file must carry a valid signature by a key in force
  before it, as a change to `signers` is checked today. Every such commit, not only the last: otherwise an unsigned
  change could be carried through by a later signed commit that touches another line.
- One such commit without a valid signature refuses the whole new `main`. The commit in force stays, and the refusal
  names the commit and the file, as a value a program reads (`following --json`) and on a line of its own.
- The new tip still descends from the commit in force, or waits for `--accept-rewrite` as today.

The protected files of the project's repository, at each commit compared, whatever the commit before named:

- `project.yml`;
- `access.yml`, where `access: {file: access.yml}` puts it beside the project file;
- `machine-signers`, beside the project file, whose keys every following machine accepts messages from;
- every file an `image.snippet_file` names, for the project and for each repository, since what it holds runs as
  root in every task image.

The access repository holds `access.yml` alone, and the same rule applies there: every commit along the first parent
that changes it is signed by a key the project's chain holds, so an unsigned change cannot be carried by a later
signed one either.

## Plan

1. `apps/app-project`: a new `ProtectedFiles` names, for one commit, the protected paths of the project's repository -
   `project.yml`, `access.yml` beside it when the project file puts it there, `machine-signers`, and every
   `image.snippet_file` of the project and its repositories - read from the committed YAML with `git show`, never from
   the working tree. `SignerHandover` walks the first-parent range it already lists (`between`), compares each commit
   with its first parent (`git diff-tree --no-renames -r --name-only`, so a rename counts at both ends), checks every
   commit touching a protected path with `ConfigurationGate.verify` against the keys in force before it, and then
   advances the signers. A failed listing refuses instead of yielding an empty range.
2. `Reconcile.reconcileProject` applies the fetched tip when the whole range passes, a document-only unsigned tip
   included; `reconcileAccess` walks the access repository's range the same way for `access.yml`.
3. The refusal names commit and file: `following --json` adds `refused_file`, `access_refused` and
   `access_refused_file` (`""` when none), carried through `Reconcile.Result`, `FollowedProjects.Followed`,
   `ProjectInventory`, `ProjectFollowingCommand` and the varlink `Followed`; `verify` refuses with 65 naming both on
   stderr, and answers `signer: ""` for an unsigned document tip.
4. Tests, red first: `ReconcileTest` - an unsigned document-only commit on an applied project is refused today
   (`NOT_SIGNED` at the tip), and an unsigned `project.yml` change under a later signed document commit is applied
   today. Then `AccessReconcileTest`, `ProjectVerifyCommandTest`, `ProjectFollowingCommandTest`: every protected path,
   a deletion, a rename, a merge on the first-parent path, a self-added signer.
5. Docs: `doc/project-file.md`, `doc/hosts.md`, `doc/commands.md`; the acceptance feature `project-follow-signed`.

## Acceptance

- A developer's unsigned commit that changes only a document, followed by nothing else, is applied.
- An unsigned commit that changes `project.yml`, under a later signed commit that changes only a document, refuses the
  new `main` and names that commit and `project.yml`; the same for `access.yml`, `machine-signers`, a named snippet
  file and the access repository's `access.yml`.
- A commit that renames or deletes a protected file is checked as a change to it.
- A commit signed by a key added to `signers` in that same commit is refused, as today.
- Seen to fail first: today's follow refuses the developer's unsigned document commit at the tip, and applies an
  unsigned change to `project.yml` that a later signed commit covers.

## To be checked

- The first follow and an accepted rewrite keep checking the tip against the anchor given, as today.
- What a range of hundreds of commits costs.

- Whether anything else the project's repository holds is read by a following machine; each one found is protected
  the same way, or the protection is bypassed through it.
- How a merge commit on the first-parent path is compared: against its first parent, so what a merged branch changed
  counts as changed by the merge commit.
- With the design accepted on 2026-10-30, one key per person: `machine-signers` goes from the protected files when
  [B344](README.md#b344) lands, and the access repository's `cards/` join them, a change of a person's own card by a
  valid change of key excepted ([B337](README.md#b337)). The same check, a signer per path, serves the registry
  ([B339](README.md#b339)).

## Done

- Both repositories check every protected change on the first-parent path with the keys in force before it;
  unsigned document changes can advance a verified baseline, and refusals identify the commit and protected file.
- Follows fetch full history and refuse shallow sources or clones. Parent relationships come from commit objects;
  missing parents, commits, trees or declaration reads refuse instead of erasing the declaration.
- Changing an unverified follow to verified checks both tips as a first follow. Neither unchecked commit is an
  applied baseline, even when the tip has not moved, and the switch needs an explicit or already pinned anchor.
- Every follow prepares its clones and trust changes before publication, including requests without a new key.
  Refusals preserve live refs and objects, shared signer bytes, followed records and existing events. Successful
  unchanged follows recover missing, unborn or misplaced project and access clones by their recorded HEADs.
  Background reconciliation prepares private copies too, so it cannot leave a newly initialized live clone empty.
  A live clone that is no work tree of its own (empty, bare, a link, dangling or not, or with its `.git` gone,
  empty or half deleted) is made again, never fetched into and never followed; publication moves a link aside as
  itself. Keeping a live clone at its recorded commit asks for a work tree too: a bare clone at that commit is made again.
- Terminal and socket previews make the same publication decision as real follows, including every access refusal.
  They check a private copy of the live clones, in the temp directory, as the follow does: a shallow live clone or
  an incremental bundle gets the same answer from both, and a preview records nothing, not even the follow folder;
  refusals report their outcome and rejected snapshot, preserve existing records and claim no pinned keys.
  Host trust and credential recovery identify the access repository when it is the source that refused.
- A fingerprint can match any verified protected project commit in the range, including an earlier handover.
  A fingerprint matching none refuses before publication; refused multi-key follows and previews claim no pins.
- Tests: `ReconcileTest` (192), `AccessReconcileTest` (32), `ConfigurationGateTest` (16),
  `ProjectFollowAccessBundleTest` (4) and `SignedByTest` (3); the whole suite 4193 green. Nine rounds of narrow
  readings by agy Gemini; each fix's test was seen red first.
