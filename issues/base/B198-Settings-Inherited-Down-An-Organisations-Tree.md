# B198 — Settings Inherited Down An Organisation's Tree

**Status:** Accepted.

**What must be true.** An organisation passes good settings down to its teams and projects, and can make some of them
binding. No project or team below can undo a binding setting, and none can slip in a forged one.

## The tree is described from the top

- **A machine's administrator pins one organisation file**, root-owned so that a developer cannot remove it, with the
  key that signs it. A command could look like `porelo org follow <url> <path> --signed-by <key>`.
- **The organisation file names its teams.** A team is written inline, or delegated to a file of its own in another
  repository, with the key that signs that file.
- **A team may contain teams again**, and lists its projects by the **repository address** of the project's
  `project.yml`, never by the project's name.

## A project gets its settings from its one path in the tree

The path is organisation, team, sub-team, then the project's own `project.yml`.

- **Merging:** maps merge key by key, and a value below replaces one above. A list replaces the list above it, except
  where a lock says otherwise.
- **Who belongs where:** the team says which projects belong to it; a project cannot place itself in a team. A project
  listed in two places is refused by name.
- **A project in no team** follows the organisation's `unlisted` setting: refused, or run with the organisation's
  settings only.
- **With no organisation pinned**, everything is as today.
- **Several organisations on one machine:** each has its own tree, and a project may stand in at most one of them.

## Locks

A block of their own beside the settings, `locks:`, naming paths of settings:

- **`fixed`:** below, the value cannot change.
- **`narrow-only`:** below, it can only get stricter.
  - A number or a duration may be smaller, never larger.
  - An allow list may be a subset, never larger.
  - A deny list may be a superset, never smaller.
- **The schema says, per key, what stricter means**, and refuses `narrow-only` on a key that has no such direction.
- **A team may add locks**, but never lift one from above.
- **A project that breaks a lock starts no task.** It gets a named refusal that names the file and the key.

## Authenticity, as following is today

- **A file applies only when its commit is signed by the key its parent names.** The parent is itself signed, so the
  chain runs unbroken to the key the administrator pinned.
- **A signed commit that does not descend from the one in force waits**, as `rewritten` does today.
- **A team's file takes effect only when it is reached through the organisation.**
- **A file that cannot be fetched keeps its last verified state.** One never verified starts no task in its branch of
  the tree.

## Seen

`porelo project show --resolved` shows every value in force, the file it comes from, and whether it is locked.

## Relation

porelo-project [PJ16](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj16), an organisation
deciding which providers its machines use, becomes one case of this: the allowed providers are a locked setting in the
organisation file. It is to be folded in at analysis.

## To be checked

At analysis (Agent Core's):

- **Which commit a file follows:** its newest signed commit or a pinned one. The proposal is the newest, as following
  does.
- **Which keys an organisation or team file may set.** The proposal is settings (`limits`, `egress`, `agents`, values),
  never `repositories` or a project's name.
- **Whether a changed lock binds running tasks or only the next start.** The proposal is the next start, as `hand_in`
  does.
