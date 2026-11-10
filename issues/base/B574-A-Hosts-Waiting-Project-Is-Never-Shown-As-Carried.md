# [B574](README.md#b574) — A Host's Waiting Project Is Never Shown As Carried

**Status:** Review. Filed 2026-11-09 under ST291, from the fourth move.
**Size:** L - the host publishes each project's follow state beside its central state, porelod reads it, the contract carries it; [B543](README.md#b543) first.

**What must be true.** While a host's follow reports a project as waiting (`waiting-for-token`, or another state that
is not carried), porelo never reports that project or its repositories as carried there.
- `projects` over the daemon names the host's state and why for the project and each repository it lists:
  `waiting-for-token` says that the host needs the person's forge token once per new repository (to add its read key
  and copy the repository, since the host keeps no token), so the window can show it on the project row, the machine's
  project page and each repository row, with the token action beside it.
- A repository the host has not placed reads "not carried", never "carried · cannot say: host-answer-refused".
- An answer of the host that porelo cannot read says which field or value it refused, not only `host-answer-refused`.

## Plan

Read in the code: "carried" comes from the organization's placement file alone (`ProjectInventory.carriedHere`,
`carriedBeforeTaken`, from `OrganizationProjects.Placed`), never from what the host's follow found; the follow's
project state (`FollowProjects`, `HostFollow`, `HostOrganization` in `host/`) reaches nothing porelo reads. The
central read (`HostCentral`, after [B543](README.md#b543) a root-published file under
`/etc/porelo-host/organization/central/`) refuses a whole answer on any field or state it does not know.

- `host/`: the follow writes the project's own `state` and `why` into the published central file beside its
  repositories, under the same file rules.
- `HostCentral` reads them; `ProjectInventory` reports `hostState`/`hostWhy` per project and repository, and
  `carriedHere` is false while the host says the project waits. Refusals name what was refused.
- The Tasks1 projects part, its interface text and `doc/` say the new fields; a changelog entry.
- Red first: a project the placement file names whose central file says `waiting-for-token` is listed with that
  state and not carried; a repository with an unreadable answer names the refused field.

## To be checked

- Why every repository of an 18-repository project read `host-answer-refused` on the move: run the host's central read
  against the follow's answer for a waiting project and name the field.

## As built

The follow publishes every applied project's own state and bounded printable explanation beside
its timestamped central measurements, including projects waiting before migration without asking
central. Separate repository progress uses logical project-file names and covers repositories that
have no measured copy yet. The existing root publication and member read ACL rules remain in force.
The first token wait explains the forge token, read key and copy action. Publication keeps the
central directory closed until updated answers succeed; a standalone distance refresh preserves
both the refreshed project's progress and every other project's publication.

The checked reader accepts the host's defined project and repository states and refuses the whole
publication on unknown or malformed data, naming the refused field or value in bounded printable
text. Valid older central files without progress retain their placement-based carrying answer.
The follow's project status row itself is not a central measurement envelope: it has progress
fields and no measurement timestamp, so feeding that shape into the old reader refused every
repository together. The original move's exact file was not available to identify its first refusal.

Projects and repository rows expose `hostState` and `hostWhy` through Tasks1. Placement remains
necessary; a waiting or refused project withholds carrying for every row, and a supplied repository
progress permits carrying only for a taken copy. Waiting rows remain visible before a local take,
and a known host state keeps an untaken project out of pruning. The contract, interface description,
host documentation and changelog describe the added fields and compatibility behavior.
