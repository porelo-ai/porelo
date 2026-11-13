# B636 — A Missing Homeserver Names The Step

```json
{
  "$schema": "issue-1.json",
  "id": "B636",
  "title": "A Missing Homeserver Names The Step",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 34,
  "blockedBy": [],
  "covers": "A start refused with no-homeserver for an organization project that names mail.transports.matrix says the step that ends it - a project admin names the homeserver and a room in project.yml and signs it - and the docs say the same."
}
```

**Why this size.** The words of one refusal in `ProjectTaking` and the docs on a project's Matrix settings.

Filed 2026-11-12 from the operator's decision on ST314. An organization project with `mail.transports.matrix` refused a
start on a person's machine with no-homeserver (exit 78). The refusal is deliberate, but it did not say who ends it or
how.

**What must be true.** The refusal names the step: a project admin names `mail.transports.matrix.homeserver` and a
room in the project's project.yml and signs that change; with the vault holding
`matrix-registration-token.<server>` where tasks register. `doc/` says the same where Matrix conversations are
set up. Read first: `ProjectTaking` (no-homeserver) and `ProjectMembers.settings`.

**Superseded.** A start is no longer refused for a missing homeserver: [B731](README.md#b731) starts the task with no
conversation instead, so there is no refusal left to name a step in.

## Built

The refusal it was filed for is gone: since [B731](README.md#b731) a placed project without a homeserver starts with
no conversation instead of being refused. What is left is the line that start writes. It now names the step that ends
it.

- `TaskConversations.noHomeserver`: the "No conversation" line says the task's agents cannot talk to each other, and
  that this ends when a project admin names `mail.transports.matrix.homeserver` and, if it wants one, a room in
  `project.yml` and signs it. It is still one line.
- `doc/project-file.md`: the paragraph on a project without a homeserver says the same step. It adds that the vault
  holds `matrix-registration-token.<server>` where tasks register, linking to transports.md.
- `TaskConversationsTest`: the no-homeserver start asserts the step's words. The test failed on the old line and
  passes with the new one.
