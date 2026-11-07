# [B488](README.md#b488) — A Machine Takes A Project And Signs Repository Files From Its Existing Checkouts

**Status:** Idea. The operator's intent, not yet Accepted.

**What must be true.** Where a person keeps a project's repositories as local checkouts, kept current by
themselves, taking the project and `porelo project sign-repository-file` work from those checkouts instead of from
clones of their own. Nothing in a checkout changes, except what a plain fetch changes.

## Found

On 2026-11-07, porelo.yml had to be signed anew in porelo-pi, porelo-omp and porelo-claude-code, on the operator's
laptop. The laptop already holds every repository as `~/git/porelo*`, as clones of the central repositories. Porelo
uses none of them:

- **Taking.** `ProjectTaking.take` runs only inside `porelo task prepare` and `porelo task run`. It fetches the
  project's own repository from the address the organization files for it, into a clone of its own under
  `$XDG_STATE_HOME/porelo/organization-projects/<project>.git`, and writes `<project>.json` beside it. Without that
  record, `sign-repository-file` refuses with "this machine has not taken the organization's project", and nothing
  else writes the record.
- **Signing.** `ProjectFile.sign` (through `TreeRepository.fetchDefault`) fetches the default branch with its whole
  history from the repository's `upstream`, as named in the judged project file, into a temporary directory. It
  commits the empty signed commit there and pushes it back to the upstream.
- No option and no setting names a local checkout. The only way around it would be to change the addresses in the
  organization or in the project file.

## Open

- **Where the checkouts are.** A setting of this machine from upstream address to path, `--from <path>` on the
  command, or the checkout a command runs in (as `approve` and `project init` do today).
- **Trust does not move.** A checkout can hold anything: local commits, another branch, an older state. The project
  file is judged by its signatures as today, whatever the bytes came from. Freshness is the open part. A checkout
  behind the upstream gives an older revision, which the memory in `organization/project-files` (and
  [B465](README.md#b465)) must refuse, not place. Whether a fetch of the upstream tip stays to confirm "newest" is to
  be decided.
- **Reading without writing.** A temporary clone that takes the checkout's objects (`git fetch <path>` over the
  `file` protocol, which `GIT_ALLOW_PROTOCOL` already allows, or alternates) and writes no ref, config or hook into
  the checkout. Porelo's git runs without the person's config and hooks, and so must every read of a checkout.
- **The signed commit.** It is still pushed to the upstream. Whether the checkout then holds it is up to the
  person's next fetch, never to Porelo.
- **With [B487](README.md#b487).** Taking moves into the organization adapter, `sign-repository-file` stays in
  `porelo`. Both need the same answer on where the checkouts are.
