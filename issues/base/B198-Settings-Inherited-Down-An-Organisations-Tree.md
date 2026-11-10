# B198 — Settings Inherited Down An Organisation's Tree

```json
{
  "$schema": "issue-1.json",
  "id": "B198",
  "title": "Settings Inherited Down An Organisation's Tree",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 13,
  "blockedBy": [],
  "covers": "Teams, settings and locks as new paths in the organization's signed tree pass settings down to projects; `fixed` and `narrow-only` locks nothing below can undo, `project show --resolved`; an organisation's allowed providers are one locked setting of it."
}
```

**What must be true.** An organisation passes good settings down to its teams and projects, and can make some of them
binding. No project or team below can undo a binding setting, and none can slip in a forged one.

Read in the code on 2026-11-04: `ProjectTaking`
(`apps/app-task/src/main/java/org/fuin/porelo/app/ProjectTaking.java`) takes projects from organization membership
or host placement and judges them with the project's signing keys. `TreeModel`
(`organization/git-tree/src/main/java/org/fuin/porelo/organization/gittree/TreeModel.java`) records owners, people,
projects, hosts and organization admins, not teams or inherited settings. Manual following and its `rewritten`
state are gone; inheritance and locks are not built.

Decided 2026-11-04: teams, settings and locks are new paths in the organization's signed tree. The root-owned pinned
file and a separate chain of keys go: the tree's own rules, the state it refuses and the host's pin carry them.

## The tree says it

- **The organization's signed tree names its teams**, as new paths beside its projects, judged by `TreeWalk` with the
  roles that held at each change. No file outside the tree counts.
- **A team may contain teams again**, and lists its projects by the names the tree already holds.

## A project gets its settings from its one path in the tree

The path is organisation, team, sub-team, then the project's own `project.yml`.

- **Merging:** maps merge key by key, and a value below replaces one above. A list replaces the list above it, except
  where a lock says otherwise.
- **Who belongs where:** the team says which projects belong to it; a project cannot place itself in a team. A project
  listed in two places is refused by name.
- **A project in no team** follows the organisation's `unlisted` setting: refused, or run with the organisation's
  settings only.
- **A project of no organization**, of this machine alone, is as today.

## Locks

A block of their own beside the settings, `locks:`, in the tree's paths, naming paths of settings:

- **`fixed`:** below, the value cannot change.
- **`narrow-only`:** below, it can only get stricter.
  - A number or a duration may be smaller, never larger.
  - An allow list may be a subset, never larger.
  - A deny list may be a superset, never smaller.
- **The schema says, per key, what stricter means**, and refuses `narrow-only` on a key that has no such direction.
- **A team may add locks**, but never lift one from above.
- **A project that breaks a lock starts no task.** It gets a named refusal that names the path and the key.

## Authenticity

The tree's, nothing of its own: `GitTreeStore` refuses a state that does not continue the one accepted, an older one or
a fork; what cannot be fetched is answered from the last accepted state and said to be stale, and a task of a project
named there is not started. A host follows by its pin (`TreePin`).

## Seen

`porelo project show --resolved` shows every value in force, the tree path it comes from, and whether it is locked.

## Relation

An organisation
deciding which providers its machines use, becomes one case of this: the allowed providers are a locked setting in the
organization's tree. It is to be folded in at analysis.

## To be checked

At analysis:

- **Who may write a team's paths:** which of the tree's roles, and whether a team has an admin of its own.
- **Which keys the organization or a team may set.** The proposal is settings (`limits`, `egress`, `agents`, values),
  never `repositories` or a project's name.
- **Whether a changed lock binds running tasks or only the next start.** The proposal is the next start, as `hand_in`
  does.
