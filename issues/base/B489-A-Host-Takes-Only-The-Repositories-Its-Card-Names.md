# B489 — A Host Takes Only The Repositories Its Card Names

```json
{
  "$schema": "issue-1.json",
  "id": "B489",
  "title": "A Host Takes Only The Repositories Its Card Names",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 74,
  "blockedBy": [],
  "covers": "What `porelo org fetch` answers a host names, per project, the repositories its card carries, so a host carrying some takes only those; seen at the trial move taking all 17 of porelo, with 17 read keys on the central repositories."
}
```

**Why this size.** one module's answer and the offline contract that holds every adapter to it; the host already reads
the field, so no other repository changes.

Built on the operator's word that every finding of the trial move is fixed before the second
move.

**What must be true.** A host whose card names some repositories of a project takes only those, plus the project's own
repository. A card that names none carries the project whole, as today. What `porelo org fetch` answers a host says,
for each project, which repositories the card names, so the host's follow can tell the two apart.

## Found

- **Seen on 2026-11-07, during the trial move.** The operator ran `porelo org host refresh porelo-move`. The
  organization at bcfec8f1 names `repositories: {porelo: [sluice]}` for that host, yet its answer said all 17
  repositories of porelo were `taken`.
- **The host acts as it was told.** porelo-host-person takes only what `project["repositories"]` of the fetch answer
  names. Absent, null or empty, it takes them all (`follow_project_state`, `follow_take_named_repositories`).
- **porelo leaves the field out.** `GitTreeOffline.project` in `organization/git-tree` writes each project's row as
  `project`, `address`, `members` and `signing_keys`, with no `repositories`. The change that let a host carry
  single repositories (27bb8e0b) put the subset into the organization's host file, `org host` and `project list`, but
  not into this answer. So every host takes every repository.

## Cost of the fault until it is fixed

- **17 read keys were registered on the central repositories**, one for each repository taken, where one, for sluice,
  was meant. Each is a read key of porelo-move's on a central repository it was never to carry.
- **The project's members reach 16 copies on porelo-move's forge** that were not meant to be there.

## Plan

- `GitTreeOffline.project` writes `repositories` from the host's card, `OrganizationQuestions.Host.repositories()`: the
  names it carries of that project. For a project carried whole the field is left out, which porelo-host-person
  already reads as "all".
- `OfflineFixture` gains `carrySome(admin, host, project, repositories)`, the host's admin narrowing the host's file to
  some of a project's repositories; `GitTreeFixture` writes it as `{name, repositories}` in `host.yml`, the stand-in as
  one more change on its line.
- `OfflineContract` gains `aHostCarryingSomeRepositoriesIsToldWhichOnes`: before the narrowing the project's row names
  no repositories, after it exactly the ones carried.
- **Red first:** the new check against `GitTreeOffline` as it is, and a stand-in break (`OFFLINE_CARRIES_WHOLE`) that
  `BrokenStandInsTest` must see caught by that check alone.

## Acceptance

- The offline contract passes for git-tree and the honest stand-in, and fails for git-tree before the fix and for the
  stand-in that drops the field.
- After the fix reaches a host, `porelo org host refresh <host>` on a card naming one repository of a project ends
  with that repository and the project's own repository `taken`, and no other.

## To be checked

- Whether porelo-host deletes its read key on a central repository when it takes that repository off. doc/commands.md
  says the host deletes its own per-repository keys itself; Agent Machines confirms it in porelo-host. If it does
  not, the 16 keys of the trial move are removed on the forge by hand.
