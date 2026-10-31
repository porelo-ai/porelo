# B354 — A Task Named By Its Key

**Status:** Idea; blocked by [B343](README.md#b343).
**Size:** S, proposed - one suffix in the task's name from its certified key's fingerprint, unique per login.

**What must be true.** A task's name carries a short suffix from its own key's fingerprint, unique among the tasks of
its login, so a name seen in a branch, a message or the host's records names one task key and no other. Two tasks of
the same name started one after the other, or by two persons, are told apart by their names alone, without asking for
their certificates (`porelo task certificate <fingerprint>`).

## Acceptance

- Two tasks of one login get suffixes that differ, even when their fingerprints share a prefix.
- The suffix is the same wherever the name appears: the container, the branch `porelo/<task>`, the principal
  `task:<project>:<task>` and the certificate's record.
- Seen to fail first: today a task's name has no part of its key in it.
