# [B488](README.md#b488) — A Repository File Signed From The Checkouts A Person Keeps

**Status:** Analyzed.
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
   `TreeRepository.standing(<a>/.git)` instead of `TreeRepository.fetch`. The kept record keeps its continuity
   check: the checkout's `main` must continue the commit last trusted, or it is refused as today, as "an older state
   served again, or a fork". What it answers is `signingKeys(project)` and the project repository's address.
   Nothing is kept from it except the trusted record as today.
2. **Project from (b).** The checkout's remote is not trusted. Its `main` holds `project.yml`, and
   `ProjectFile.standing` judges it with the keys from step 1. The memory in `organization/project-files` refuses an
   older revision than the one this machine accepted, as the taking does. The judged file gives the `upstream` of the
   repository (c) is named as.
3. **Signing in (c).** This is a new `ProjectFile.signStanding(change, checkoutGitDir, upstream, keys)` beside
   `sign`:
   - It opens a scratch bare repository whose `objects/info/alternates` names (c)'s object directory. Porelo then
     reads and writes nothing in (c) except one ref, and the checkout's own config, hooks and `insteadOf` never
     apply.
   - Its `main` starts at (c)'s `main`. It checks that the acting key is an admin's or developer's key, valid today,
     and that (c)'s `main` holds `porelo.yml`.
   - It makes the empty signed commit, with `Porelo-Signs-Anew: porelo.yml` and `Porelo-Change-Id`, in the scratch
     repository.
   - It pushes `main:main` to `upstream`, fast-forward only.
   - Only after the push is taken does it run `update-ref refs/heads/main <new> <old>` in (c). A refused push leaves
     (c) as it was.
   - A `main` that moved meanwhile makes `update-ref` fail. The answer then says the forge holds the signature and
     a fetch brings it.
4. **Command.** `porelo project sign-repository-file` gets the form from the open question below. Without `--yes`
   it shows (c)'s `porelo.yml` at `main`, its newest change and its signer, as `lookRepositoryFile` does today but
   in place. `--yes` signs that `main`. There is no `--base`: what is signed is the tip of the checkout the person
   read. The `--id` retry stays.
5. **Docs and contract.** Update the `doc/commands.md` row and `doc/roles.md`, and add a changelog entry.

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
- An acceptance step on the VM runs this with a checkout prepared from the forge-on-VM repositories.

## Open questions

- **How the three checkouts are named.** The recommendation:
  - (a) and (b) as settings of this machine, made once (`porelo setup --organization-checkout PATH`, and the
    project's checkout per project).
  - (c) as the directory the command runs in, or `-C PATH`.
  - Alternatively, all three as options on each run.
- **The old form.** Does `PROJECT REPOSITORY --yes --base` stay beside this for a machine without checkouts, such as
  a host after its follow? The recommendation is to keep it: a host has no checkout, and removing it is a separate
  decision.
- **(c)'s `main` ahead of the forge.** With local commits not yet pushed, the fast-forward push would carry them
  along under the signature. The recommendation is to refuse unless the forge's `main` (one `ls-remote` of the
  upstream) is the parent of the signed commit, and to say which commits are only local. The alternative is to sign
  and push them along on purpose.
- **(c)'s remote.** The push always goes to the `upstream` the judged project file names, whatever (c)'s `origin`
  says. A checkout whose `origin` names another address: the recommendation is to refuse, since it is then probably
  not the repository the person thinks; the alternative is to sign anyway.

## To be checked

- `TreeWalk` on a person's machine takes whose keys are whose from the forge's public list (`HttpForgeDirectory`),
  which is a network read. Does a walk from a checkout still ask it? Then the organization's forge must answer;
  without it the founder's key is unanchored. Read whether the kept record alone carries a later walk, and say
  which before Estimated.
- Whether `alternates` from a scratch repository to a checkout another account owns works without
  `safe.directory`, as `standing` does with an explicit git directory.
- That `update-ref` in (c) runs no `reference-transaction` hook under `core.hooksPath=/dev/null`, measured.
- A checkout made with `git worktree add` or as a submodule (`.git` a file): resolve it to its git directory, or
  refuse it.
- [B487](README.md#b487): reading the organization belongs to the organization adapter there, and
  `sign-repository-file` stays in `porelo`. That contract would need a question "signing keys of a project, read from
  a checkout at PATH". Built here in today's modules until that is decided.
