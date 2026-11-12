# B518 — A Person Leaves An Organization

```json
{
  "$schema": "issue-1.json",
  "id": "B518",
  "title": "A Person Leaves An Organization",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 25,
  "blockedBy": [],
  "covers": "`porelo org leave`: a person withdraws their own card and roles and forgets the organization on this machine, refused where it would leave no owner; the window's *Leave…* runs it."
}
```

**Why this size.** `porelo org leave`, on code that exists for revoking a card, plus forgetting the organization here.

Backlog since 2026-11-09, in ST295. Filed 2026-11-08.

**What must be true.** `porelo org leave` takes a person out of an organization in one step.
- It withdraws their own card, as `porelo org person revoke <me>` does.
- It gives up their own roles: owner, admin, host admin and project admin.
- It forgets the organization on this machine, which `porelo setup` cannot do today: it has only `--organization` and
  `--replace`.
- It refuses to leave the organization without an owner, and says who could take the place.
- It answers `--json`, so the window's *Leave <organization>…* can run it.

## Why

Agent Frontend's window shows *Leave <organization>…* as a waiting action. Nothing does it today: `org owner
remove`, `org admin remove` and `org person revoke` act on one part each, and nothing forgets the organization here.

## Plan

Read in today's code: `apps/app-organization` `OrgWriting` (one signed change, pushed against a base),
`OrgPersonCommand.Revoke`, `OrgOwnerCommand.Remove`, `OrgAdminCommand.Remove`, `OrgSetup` (the address, the kept own
card and key, the checkouts); `organization/api` `OrganizationWrites`; `doc/roles.md`.

**What the rules let a leaving person change.** Each path is judged alone by its signer, so `leave` writes only what
the person's own key may change, against the state before the change:

- `owners.yml`, as an owner, taking themselves out;
- `organization-admins.yml`, only when they are an owner too, since only an owner changes it;
- their own `people/<login>.card`, removed by its own key, which revokes the key.

A machine's or project's admin list cannot be changed by its own admin (`doc/roles.md`: an organization admin changes
those). Once the card is gone its key holds no right anywhere, so such an entry stays written and grants nothing; the
answer names each one, so an organization admin can tidy it.

1. **`porelo org leave`.** One `OrgWriting`-style session: the changes above as consecutive signed commits, the card's
   removal last, pushed together against the base it read, so a concurrent change refuses the whole leave and none of
   it lands. Red first: an owner and organization admin who leaves is in neither list and has no card; a project
   admin's entry is answered as left for an organization admin.
2. **No owner left.** Refused before anything is written when the person is the last owner in `owners.yml`; the
   refusal names who could take the place: the organization admins, else every person holding a trusted card. Red
   first: the last owner's leave leaves the repository unchanged and names the admins.
3. **Forgetting the organization here.** After the push stands, this machine's settings drop the organization's
   address, the kept own card and `key.pub`, and the organization and project checkouts named, so `porelo setup
   --organization` can point it at another one. Nothing outside porelo's own settings is touched; the checkouts
   themselves stay where the person keeps them. Red first: after a leave, the context answers no organization.
4. **`--json`** answers `{organization, login, left: [roles], stillListed: [{file, role}], forgotten: true}`; a
   refusal answers its words and exit code as the other `org` writes do. The shape is told to Agent Frontend in the
   channel, for *Leave <organization>…*.
5. `doc/commands.md` and `doc/roles.md` on leaving, the changelog.

All tests are unit tests over a local organization repository, as `OrgPersonCommand`'s are.

## To be checked

- Whether `OrgWriting`'s session can push several commits at once, or `leave` needs that added.
- Which other files porelo keeps per organization on a machine (a cached tree, a pin); each is forgotten with it.
