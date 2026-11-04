# B406 — A Revoked Person Comes Back Only When Agreed

**Status:** Idea.
**Size:** M - a rule in the walk that every reader holds, a contract check, the way an owner or admin agrees, and words in
the commands and the window; no new file format is expected, not read through yet.

**What must be true.** After a person's card is revoked, they come back only when an owner or an admin agrees. A new key
listed at the forge and *Add me* alone do not bring them back. This is the operator's word of 2026-11-03.

**What it stops, by reading, not run.** A revoked key can file nothing, but `addCard` takes a new card for a login whose
card stands refused, and the walk judges that card by the key the forge lists. So a person an owner removed, who can still
add a key to their forge account, can come back with no one agreeing.

**To be settled when it is planned:**

- Whose agreement counts: which admins, an organization's owners alone or a project's admins as well.
- What agreeing is: an organization admin's `org person replace`, which exists for a lost key, or a step of its own.
- How a lost key's replacement stays apart: its old card is revoked too, and an organization admin's acceptance already
  brings that person back, which this must not break.
- What a reader that accepted the person earlier does, and what stands of what they signed while they were gone.

Planned after the operator's tests; nothing built yet.
