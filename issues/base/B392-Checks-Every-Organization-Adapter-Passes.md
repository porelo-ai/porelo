# B392 — Checks Every Organization Adapter Passes

**Status:** Review.
**Size:** M - one module of checks over the API, a fixture for the Git adapter, and a broken adapter per requirement to
show each check fails.

**What must be true.** What an organization's store must guarantee is written down as checks any adapter runs against
itself: every change signed by the one allowed to make it; a history that cannot be rewritten unnoticed, so no
revocation and no older card is played back; and a host's offline check against its pinned key. The Git adapter passes
them; an adapter that accepts an unsigned change, a played-back older state or a revoked card fails them.

## Plan

- **`organization/contract`** (`porelo-organization-contract`): JUnit tests over the API alone, needing no Git. An
  adapter gives a fixture that makes a store and plays each person, and that plays the attacker in its own way: a change
  nobody allowed made behind the store's back, an older state served again, a revocation discarded.
- **The checks are drawn from the existing tests**, which stay where they are since they read Git's details: the
  signers' cases of `RegistryHistoryTest` and `RegistryFileCommandTest`, the rollback and fork cases of
  `RegistryHistoryTest` and `RegistryVerifyCommandTest`, and the pin's cases of `RegistryVerifyCommandTest`.
- **Git's fixture** in `organization/git-tree`'s tests, the attacker as raw commits on the repository.
- **A broken stand-in for each requirement** in the contract's own tests, an in-memory store that accepts what it must
  refuse, so each check is seen to fail.
- The requirements in words in `doc/decisions.md`, as what any store must meet.
- Red first: the checks against the broken stand-ins, before the Git fixture exists.

## Acceptance

- Every check passes against Git and fails against the stand-in that breaks its requirement.
- The module needs neither `git` on the machine nor a class of `organization/git`.
