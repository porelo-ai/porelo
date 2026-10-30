# B352 — A Person's Access Written By Porelo

**Status:** Idea. Placed in sprint 6m by the Coordinator (2026-10-30), operator confirms 2026-10-31.
**Size:** M, proposed - one verb writing the access file and its cards, signed through the agent, idempotent by id.

**What must be true.** The project admin's Porelo writes a change to a project's access repository and signs its commit
with the admin's key through their own agent; the interface never writes `access.yml` or `cards/` itself.
`porelo project access <init|add|remove|lock|unlock> --project P --person L [--host H] --base REV --id ID --json`
answers `{"revision"}`, the commit it made:

- `init --project P --person L --host H --id ID` writes the project's first access file in the persons' form: the admin as
  its first person, role `admin`, `hosts: [H]`, with their card. It takes no `--base` and is refused when an access file
  exists; then the admin is added to another host by `add`.
- `add --host H` puts the person into `persons:` with their card in `cards/L.card` ([B342](README.md#b342)), or adds H to
  their `hosts`; `remove --host H` takes H out of their `hosts`, and the person goes when no host is left; `remove`
  without `--host` takes them off every host at once. `lock` and `unlock` mark them `locked` for the project.
- `--base` (every action but `init`) is the revision the change was made against: when the head of the access repository is another one, the
  change is refused with exit 65 naming both, so the caller reads again and asks anew.
- `--id` makes it idempotent: the same id with the same change answers the same revision without a second commit; the
  same id with another change is refused.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH. Signing through the
person's agent is [B338](README.md#b338)'s.

## Acceptance

- `init` makes the first access file with the admin on H; a second `init` is refused.
- `add` makes a commit signed by the admin's key that `project verify --json` answers with the person and their card.
- `remove --host H` of a person's last host removes them; `remove` without `--host` removes them from every host.
- `lock` answers the person `locked: true`; `unlock` `false`.
- A stale `--base` is refused naming both revisions; a repeated `--id` answers the same revision with no new commit.
- Seen to fail first: there is no verb that writes the access file today.
