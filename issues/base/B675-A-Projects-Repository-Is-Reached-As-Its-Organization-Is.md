# B675 — A Project's Repository Is Reached As Its Organization Is

```json
{
  "$schema": "issue-1.json",
  "id": "B675",
  "title": "A Project's Repository Is Reached As Its Organization Is",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A person whose organization is reached over ssh writes a project's repository on the same forge over ssh too, so `project sign` and the project's other file writes need no forge token when the organization needs none."
}
```

**Why this size.** one address rule where a project's file is written, its tests, and a line in the docs; no contract.

Filed 2026-11-12 from an operator test's VM run, straight into the backlog as the bug rule says.

**What was seen.** With [B673](README.md#b673)'s build, `porelo project sign foo-project --yes --base <commit> --id <id>` answered "cannot
read the organization's repository: error: unable to read askpass response from '/bin/false'; fatal: could not read
Username for 'https://github.com': terminal prompts disabled" (exit 69). The signing account's organization.yml names
the organization over ssh (`git@github.com:<owner>/<org>.git`), its forwarded agent holds the key, and no forge token
is kept.

**Found, read in the code.** `project sign` does not write the organization's repository: it writes the project's own
repository (`ProjectFile`), at the address the organization files for the project
(`ProjectRepositoryCommand.place` → `getProjectRepository`), which `project create-repository` filed as the forge's
https clone URL. Over https Porelo lends only the forge token (`OrganizationAccess.lent`, `GIT_ASKPASS=/bin/false`),
so a person who reaches the organization by key alone cannot write a project's file. The refusal's lead also names the
organization's repository where the project's was read (the lead `TreeRepository.fetch` gives every clone).

**What must be true.** A project repository on the organization's own forge is reached the way this person reaches the
organization: over ssh when organization.yml names it over ssh, over https otherwise. An address on another forge is
used as filed. The refusal names the repository it could not read.

## Plan

- Where a project's file is read or written for this person (`ProjectRepositoryCommand.place` and the readers of
  `getProjectRepository` that clone), turn `https://<forge>/<path>` into `git@<forge>:<path>` when the organization's
  own address is the ssh form on the same host; never the other way, and never another host.
- The lead of a failed fetch names the repository read: the project's, not the organization's.
- Red first: a project filed at `https://forge.example/o/p.git`, the organization at `git@forge.example:o/org.git`;
  the sign's git is asked `git@forge.example:o/p.git`.

## Acceptance

- `project sign` with an ssh organization and no forge token signs the project's file; a test fails while the https
  address is used.
