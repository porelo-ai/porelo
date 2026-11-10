# B483 — A Waiting Host Approval Outlives Its Task's Removal

```json
{
  "$schema": "issue-1.json",
  "id": "B483",
  "title": "A Waiting Host Approval Outlives Its Task's Removal",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": [],
  "covers": "Work waiting at a host's gate can still be approved as its task after the task is removed, or the removal says it cannot and how else; the forge token's lifetime is decided first."
}
```

**Why this size.** a decision on the forge token's lifetime first; then removal, the gate's approval and their tests.

Filed on the Coordinator's word (2026-11-06), from [B481](README.md#b481)'s review.

**What must be true.** Work a task pushed to the gate can still be approved on a host after the task is removed, and
the pull request it opens is still the task's own, or the removal says plainly that the waiting work can no longer be
approved as the task and how to approve it otherwise.

## Found

- On a host, `gate approve` opens or reuses the task's pull request with the task's forge token
  ([B481](README.md#b481), `HostPullRequests`, the token from `TaskSecrets.forgeToken`).
- Removing a task retires its forge identity and forgets its secrets, the forge token among them, even while its push
  still waits at the gate. A pending ref outlives the container; the credential does not.
- After the removal, `gate approve` pushes the branch and then fails at the pull request: "no usable forge token is
  kept for this task". The approval stays pending, with nothing that can complete it.
- A rescued ref (work the task never handed back, kept at its removal) has the same gap, and no task identity of its
  own to open a pull request with.

## Open questions

- Keep the token until the task's last waiting push is approved or rejected, and revoke it then; or refuse a removal
  while host approvals wait, naming them; or open the pull request as the approving person instead.
- Whether a token kept for that purpose may do anything but open a pull request.
