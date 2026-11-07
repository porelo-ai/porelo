# [B488](README.md#b488) — A Repository File Signed From The Checkouts A Person Keeps

**Status:** Review.
**Size:** M - one contract change of a command, read and signing paths in `organization/git-tree` beside today's,
docs and an acceptance step; one repository.

**What must be true.** Porelo needs exactly four things to sign a repository's `porelo.yml` anew, and nothing
else:

- (a) the organization's repository, as a local checkout;
- (b) the project's repository, as a local checkout;
- (c) the repository to sign, as a local checkout;
- (d) the person's signing key.

It checks (d) against the project's signers from (a) and (b). There is no taken project, no clone and no `--base`.

- **No project is taken.** The organization's checkout (a) (here `porelo-org`: people's cards, projects, admins) is
  checked as a machine checks its organization today: its signatures against the pin, read in place. The
  project's checkout (b) is judged with the keys from (a), as the taking judges `project.yml` today. Together they
  name the project's signers. The checkout to sign (c) gives `porelo.yml`.
- **No `--base`.** The person signs their checkout's `main` as they read it. The empty signed commit goes on top of
  that `main` without touching the branch that is checked out, through a worktree or `commit-tree`. It is pushed
  with an ordinary fast-forward push. If the forge moved on in the meantime, git itself refuses the push; the person
  fetches, reads again and signs again. Nobody can come between reading and signing.
- **No clone of its own.** The three checkouts are used as they are, and only fetched. Nothing in (a) or (b)
  moves. In (c) only `main` moves, and only by that one commit.

## Found

On 2026-11-07, porelo.yml had to be signed anew in porelo-pi, porelo-omp and porelo-claude-code, on the operator's
laptop. The laptop already holds every repository as `~/git/porelo*`, but today's code uses none of them:

- `ProjectSignRepositoryFileCommand` needs a taken project (`OrganizationProjects.find`). Only `ProjectTaking.take`
  writes that record, and only `porelo task prepare` and `porelo task run` call it. The taking fetches the project's
  own repository into a clone under `$XDG_STATE_HOME/porelo/organization-projects/<project>.git`.
- `ProjectFile.sign` fetches the repository's default branch with its whole history from the `upstream` of the judged
  project file (through `TreeRepository.fetchDefault`), into a temporary directory. It needs `--base` equal to the tip
  it fetched, commits there and pushes to the upstream.

## Plan

Read in today's code. Most of it exists and is reused:

- `TreeRepository.standing(gitDir, runner)` already reads a repository in place, with a scratch directory of its
  own, and leaves it on close. `ProjectFile.standing` and `RepositoryFileJudge.judgeStanding` use it today.
- `TreeRepository.commit` makes the commit with `commit-tree -S` on a scratch index (`GIT_INDEX_FILE`). It never
  touches a working tree or the checkout's index, and then moves the branch with `update-ref <branch> <new> <old>`.
- `TreeRepository.push` pushes `main:main` with `--no-verify`, never forced. Its git runs with
  `core.hooksPath=/dev/null`, `core.fsmonitor=false`, `credential.helper=` and no global or system config.

Steps:

1. **Organization from (a).** `GitTreeStore` gets a read from a checkout: the same `TreeWalk` as `walk()`, on
   `TreeRepository.standing(<a's git directory>)` instead of `TreeRepository.fetch`. The kept record keeps its
   continuity check: the checkout's `main` must continue the commit last trusted, or it is refused as "an older state
   served again, or a fork". It answers `signingKeys(project)` and the project repository's address.
   - The walk still asks the forge's public key list (`HttpForgeDirectory`), as today. A person's machine has no
     pin: `TreeWalk.founding` anchors the founder in that list when no record is kept, and `listed` judges each
     card the walk meets after the record. A walk from a checkout therefore makes this one network read, reading
     only. It is the same read the taking makes today, and a forge that does not answer leaves cards undecided, as
     today.
2. **Project from (b).** The checkout's remote is not trusted. Its `main` holds `project.yml`, and
   `ProjectFile.standing` judges it with the keys from step 1. The memory in `organization/project-files` refuses an
   older revision than the one this machine accepted, as the taking does. The judged file gives the `upstream` of the
   repository (c) is named as.
3. **Signing in (c).** This is a new `ProjectFile.signStanding(change, checkoutGitDir, upstream, keys)` beside
   `sign`:
   - **Resolving the checkout.** (c)'s git directory is resolved with `git rev-parse --path-format=absolute
     --git-common-dir`, which handles a `git worktree` checkout too: its `.git` is a file, and `main` and the
     objects live in the common directory. A checkout owned by another account is refused, since Porelo signs only
     this person's own checkouts.
   - **Origin.** (c)'s `remote.origin.url` must name the judged `upstream`, or it is refused naming both.
   - **Ahead of the forge.** One `ls-remote` of the upstream's `main` must equal (c)'s `main`. Otherwise it is
     refused, naming the commits only (c) holds (`rev-list <forge>..main`), or saying that the forge's tip is not in
     (c) and a fetch is needed.
   - **The commit.** A scratch bare repository has `objects/info/alternates` naming (c)'s object directory. Its
     `main` is set to (c)'s `main`, and the acting key is checked as an admin's or developer's key, valid today.
     `TreeRepository.commit` makes the empty commit there, signed through the agent, with
     `Porelo-Signs-Anew: porelo.yml` and `Porelo-Change-Id`.
   - **The push.** It pushes `main:main` to `upstream` from the scratch repository, never forced. (c)'s config,
     hooks and `insteadOf` never apply, because git runs on the scratch repository.
   - **Into (c).** Only after the push is taken does Porelo write the one commit object into (c) and move `main`
     (`TreeRepository.handBack`). It runs `hash-object -t commit -w --no-filters` with the commit's text, which
     must give back the same id, then `update-ref refs/heads/main <commit> <old>`, both with
     `core.hooksPath=/dev/null`. No transport is involved, so no address rewrite of (c) applies. A refused push
     leaves (c) as it was.
   - **A `main` that moved meanwhile** makes `update-ref` fail. The answer then says the forge holds the signature
     and that a fetch brings it.
   - **Measured 2026-11-07:**
     - `update-ref` alone fails, because the commit object exists only in the scratch repository. Writing the one
       object first, by fetch in the measurement and by `hash-object` as built, puts it into (c).
     - With `core.hooksPath=/dev/null` a `reference-transaction` hook in (c) does not run. As a control, it runs
       without that setting.
     - An `insteadOf` in (c)'s config does not redirect the push.
     - `git status` in (c) stays clean, and `refs/heads/main` is the only ref.
4. **Command and settings.**
   - `porelo setup --organization-checkout PATH` and `porelo setup --project-checkout PROJECT=PATH` each record a
     checkout once, in this machine's settings. They check that it is a git checkout of this account.
   - `porelo project sign-repository-file [-C PATH] [--yes] [--id ID] [--json]` signs the checkout it runs in, or
     the one at `-C`, and finds the repository by the judged `upstream` its `origin` names. With no `PROJECT
     REPOSITORY` arguments it takes this form.
   - Without `--yes` it shows (c)'s `porelo.yml` at `main`, its newest change and its signer, as `lookRepositoryFile`
     does, but in place.
   - `--yes` signs that `main`. There is no `--base`: what is signed is the tip of the checkout the person read.
   - The old form `PROJECT REPOSITORY [--yes --base REVISION]` stays as it is, for a machine without checkouts.
5. **Docs and contract.** Update the `doc/commands.md` row, the `porelo setup` row and `doc/roles.md`, and add a
   changelog entry.
6. **[B487](README.md#b487).** Reading the organization there belongs to the organization adapter, and
   `sign-repository-file` stays in `porelo`; that contract would need a question "signing keys of a project, read
   from a checkout at PATH". This is built here in today's modules and does not wait for that decision.

After the review by another model, these points were added to the plan:
- A project's checkout is judged with the keys of the name it is kept under. A `project.yml` naming another project
  is refused before this machine remembers its date.
- A signing asked again under its `--id` is found only when the forge holds it. A local commit that carries the id is
  treated like any other local commit.
- A forge without `main` is refused as it stands.
- (c) is refused when its top, its `.git`, its `gitdir:` or its `commondir` belong to another account, each judged
  where its links lead.
- The hand-back writes nothing into (c) when its `main` moved. Moving `main` appends one reflog line naming Porelo,
  as git's own commands do.
- `origin` is read with `--no-includes` and is the first of several.

The test that goes red first is in `ProjectFileTest`. Two bare "forge" repositories and a checkout of each are set
up:
- Signing from the checkout pushes exactly one empty commit, and the checkout's `main` moves by that commit while
  its index, working tree and other refs stay byte for byte.
- A forge that moved past the checkout refuses the push, and the checkout's `main` is unchanged.

## Acceptance

- Run on a machine that has taken no project (no `organization-projects/`), with (a), (b) and (c) as checkouts, the
  sign is pushed. `$XDG_STATE_HOME/porelo/organization-projects/` stays absent. No new clone is made outside the
  scratch directory, which is gone afterwards.
- In (c), only `refs/heads/main` changed, by one commit whose parent is the old `main`. `git status` is the same
  before and after. A hook in (c) that writes a file is not run.
- Seen to fail:
  - A forge ahead of (c) is refused by the push, and (c) is unchanged.
  - A key that is no admin's or developer's of the project is refused.
  - (a) rewound behind the record last trusted is refused.
  - (b) with an older `project.yml` than the one accepted is refused.
  - (c) with an `insteadOf` pointing elsewhere still pushes to the project's `upstream`.
  - (c) with a local commit the forge lacks is refused, naming that commit.
  - (c) whose `origin` names another address is refused, naming both.
- A `git worktree` checkout signs like a plain one.
- An acceptance step on the VM runs the native binary: `porelo setup` names the checkouts and keeps them in
  `checkouts.yml`, a plain directory is refused, and `sign-repository-file -C` says what is missing and refuses
  `--base`. A signing on the VM end to end would need an organization with a forge that vouches for its keys, which
  the suite does not hold; the signing itself is covered by the unit tests on real git repositories
  (`ProjectFileCheckoutTest`, `ProjectSignRepositoryFileCheckoutTest`).
