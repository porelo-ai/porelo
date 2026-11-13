# B675 — A Project's Repository Is Reached As Its Organization Is

```json
{
  "$schema": "issue-1.json",
  "id": "B675",
  "title": "A Project's Repository Is Reached As Its Organization Is",
  "state": "Review",
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

## Built (2026-11-12)

- `OrgAccess.reached` gives a project repository's address the form this person reaches the organization in:
  `https://<forge>/<path>` becomes `git@<forge>:<path>` when organization.yml names the organization as
  `git@<forge>:...`, or `ssh://git@<forge>[:port]/<path>` for the `ssh://` form, with its port. Another host, an
  https address with a port of its own, or an organization named over https keep the address as filed.
- `project sign`, its repository-file and checkout forms and `project show` read and write the project's repository at
  that address.
- A Codex reading of that found two Reds, fixed: the memory of the revisions accepted was keyed by the address
  read, so a read over ssh had no floor from those accepted over https; it is now keyed by the https form of the
  address (`ProjectFile.oneForm`). A task's start (`ProjectTaking`) and `project create-repository` still read and
  checked the https address; both now use the reached one, while the address filed stays https. The rewrite moved to
  `OrganizationAccess.reached` in app-model so the task's start can use it. A Yellow is kept: a path's `%20` or `..`
  is carried into the ssh address as it is, which git then reads as the same path on the same forge.
- The new memory test was red only as a compile failure, since `oneForm` did not exist before. The start's and create-repository's
  use of the reached address have no test of their own yet.
- Codex read that again and found three Reds, fixed: a host compared with `equalsIgnoreCase` took a dotless `ı` for
  `i`, so a lookalike forge would be reached over the organization's key; hosts are now ASCII only and compared as
  ASCII. The one form kept the host's case, a trailing slash, `:443` and `.git`, so a respelling had no floor; it now
  drops all four. A floor kept before under the address as given was no longer read; the later of the two keys holds.
- Kept, from that reading: two ssh ports of one host share a floor, which can only refuse. A task's start whose second
  read of organization.yml fails fetches as filed, over https, where no token means it fails; the signatures are
  checked either way.
- Seen: the lookalike test failed against the old compare; the spelling and old-floor tests could not compile there,
  since `oneForm`'s new rules and `accepted(Path, String)` did not exist. The modules whole and apps/app whole
  (4608) passed, but for McpWiringTest's stop under the host's load, which passed alone.
- Built after the reading: the three Reds of the brief were fixed already. One floor was still lost on upgrade: 0.5.0
  kept it under the address as filed, `https://forge.example/acme/p.git`, and a project the organization now reaches
  over ssh read only the one form and the ssh address. `ProjectFile.accepted` now also reads the https address the ssh
  one was made from, with the host as spelled and in lower case; the latest of all keys holds. A filed host in mixed
  case that the organization spells otherwise is still not found. The test failed with no floor and passes;
  organization/git-tree whole (372) passed.
- Not done here: the lead of a failed clone still says "the organization's repository" for a project's repository;
  `TreeRepository.fetch` gives every clone that lead, and tests pin it.

## Acceptance

- `project sign` with an ssh organization and no forge token signs the project's file; a test fails while the https
  address is used.
