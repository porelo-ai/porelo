# [B489](README.md#b489) — A Host Takes Only The Repositories Its Card Names

**Status:** Accepted; filed as Accepted on the Coordinator's word, 2026-11-07T14:19Z. It is built only on the
operator's word, after the trial move.
**Size:** not estimated yet.

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

## Plan (to be estimated)

- `GitTreeOffline.project` writes `repositories` from the host's card: the names it carries of that project, or none
  for a project carried whole.
- The contract test of the offline answer (`OfflineContract`) holds both: a card naming some gives exactly those, and
  a card naming none gives none.
- **Red first:** the contract test against today's answer, which has no `repositories`.
- **Undo on porelo-move:** with the fix on the host, the next follow takes off every repository named no longer. One
  whose work has not reached its central repository stays `held`, and the project's own repository stays. Whether
  removing a host repository also removes its read key on the central repository is to be read in porelo-host before
  the estimate. doc/commands.md says the host deletes its own per-repository keys itself, and `org remove-read-key`
  removes only the organization's key. If the host does not, the 16 keys are removed on the forge by hand.
