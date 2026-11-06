# [B477](README.md#b477) — An Admin Signs A Repository's porelo.yml Anew

**Status:** Accepted, on the operator's word (2026-11-06). Not started before [B470](README.md#b470) is done.
**Size:** S - one verb on the pattern of `porelo project sign`, for a repository's `porelo.yml`; its tests. Estimate 45
minutes.

**What must be true.** A project admin or developer makes a repository's `porelo.yml` in an organization's project
taken again with one command, without writing git by hand: `porelo project sign-repository-file <project>
<repository>` reads the file at the repository's default branch, shows it and its newest change, and with `--yes
--base <commit>` makes an empty commit signed with the person's card key, carrying the trailer `Porelo-Signs-Anew:
porelo.yml`, and pushes it to the address the project files for that repository. `--id` makes a retry safe, as for
`project sign`.

## Found

- A start takes an organization's `porelo.yml` only when its changes since the last one taken are signed by a project
  admin or developer ([B470](README.md#b470), `RepositoryFileJudge`), and an empty commit whose trailer names the file
  counts as its newest change (`TreeRepository.signingAnew`). Today a person writes that commit with git by hand.
- `ProjectFile.sign` does the same for `project.yml`: read, check the acting key and the base, an empty signed commit
  with the trailer, push with the credential declared for the address, the `--id` looked for first.

## Plan

- `ProjectFile.sign` generalised over the file and the signer rule: `project.yml` wants a project admin, `porelo.yml`
  a project admin or developer, each valid today.
- The verb in `ProjectRepositoryCommand` or beside it: the repository's address from the project file's
  `repositories`, the project's keys from the organization's state, `--json` as `project sign` answers.
- Red tests: a developer's key signs it and a start then takes the file; a key of nobody in the project is refused
  `not-allowed` before any push; a moved tip is `moved-on`; a retry with the same `--id` finds its commit.
