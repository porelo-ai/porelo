# [B488](README.md#b488) — A Repository File Signed From The Checkouts A Person Keeps

**Status:** Accepted (operator, 2026-11-07).

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

## Open

- **Naming the checkouts.** A setting of this machine, an option, or the checkout the command runs in.
- **A checkout behind the forge is not signed.** The refused fast-forward push covers that. Still open: a `main`
  that is *ahead* of the forge, with local commits not yet pushed. Signing on top would push those along. Refuse
  unless `main` equals what the forge has, or say so before signing.
- **Which remote.** The push goes to the repository's `upstream` as the project names it. A checkout whose remote
  names another address is refused, rather than pushed to wherever it points.
- **The person's git setup.** Reading and the one commit run without the checkout's hooks and the person's config,
  as Porelo's git runs today (`core.hooksPath=/dev/null`, no global or system config). The signature is made through
  the person's ssh agent, as now.
- **The checked-out `main`.** An empty commit carries `main`'s own tree, so moving the ref leaves the index and the
  working tree matching it. Whether a worktree or `commit-tree` plus `update-ref` is the safer way is decided when
  this is built.
- **With [B487](README.md#b487).** Reading the organization belongs to the organization adapter; `sign-repository-file`
  stays in `porelo`. The adapter has to answer the signer keys from a checkout it is pointed at.
