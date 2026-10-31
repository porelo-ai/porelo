# B376 — An Allowed-Signers Line Read As Ssh-Keygen Reads It

**Status:** Idea.
**Size:** M, proposed - every reader of a pinned or given allowed-signers line made to read it through one parser,
the signer found as git finds it, ssh-keygen's blanks, a commit's own encoding, and git's whole signature, each with
its test.

**What must be true.** Every place that reads an allowed-signers line reads it as ssh-keygen does, and the host judges
a pushed commit exactly as `git verify-commit` does. A second reading of the follow-up of
[B366](README.md#b366) on 2026-10-31 found six places where this does not hold yet:

- A pinned line whose principal is quoted (`"demo" ssh-ed25519 ...`) is read as `demo` by the gate and the handover,
  but `Reconcile`'s staging, `FollowedProjects.unfollow` and `ProjectEnrollCommand.pinned` still split it at the first
  blank, so a retired or unfollowed key stays pinned and in force.
- `porelo commit verify` checks a person's signature with the principal `porelo` in place of the line's principals,
  so a line git refuses (`!alice`, `alice,!alice`, `""`) is accepted, and when a key has several lines the signer
  answered is the first line's, not the one git finds with `ssh-keygen -Y find-principals`.
- A vertical tab, form feed or Unicode space counts as a blank, where ssh-keygen takes only space and tab, so a line
  ssh-keygen refuses is taken and a principal like ` task:demo:*` is read as `task:demo:*`.
- A commit in another encoding (`encoding ISO-8859-1`) is decoded as UTF-8 before its signature is checked, so a
  commit git accepts is refused.
- Only the first `gpgsig` header is taken as the signature, where git appends every one, and a test asserts the
  first only.
- Lines after `-----END SSH SIGNATURE-----` or a non-empty reserved field make the signer unreadable, where
  ssh-keygen and git accept the signature.

Each is answered by a test that fails today.
