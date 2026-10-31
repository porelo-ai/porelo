# B376 — An Allowed-Signers Line Read As Ssh-Keygen Reads It

**Status:** Review.
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

## Done

- The first three were measured with git 2.53 and OpenSSH 10.2 and are real. ssh-keygen reads `"demo" ssh-ed25519 ...`
  and `demo<tab>ssh-ed25519 ...` as demo's. `git verify-commit` refuses a key whose line names `!alice`,
  `alice,!alice`, `*,!*`, `""`, `","` or `,alice`; of several lines for one key it verifies by the principals
  `ssh-keygen -Y find-principals` prints for the first line in force, so `alice-mail namespaces="mail"` before `bob`
  is refused and `alice-future valid-after="20990101Z"` before `bob` is accepted as bob. ssh-keygen parts a line only
  at a space or a tab: a vertical tab, form feed or U+2000 between principal and key leaves the line unread, and a
  leading U+2000 stays in the principal.
- `Reconcile`'s staging and `FollowedProjects.unfollow` find a project's pinned lines through `AllowedSigner.parse`,
  and `project enroll` names the pinned fingerprints through `PinnedSigners`, so a retired or unfollowed key pinned
  under a quoted principal, a tab or a leading blank leaves force.
- `porelo commit verify` writes a person's lines in force for the key with their own principals, asks `ssh-keygen -Y
  find-principals` at the arrival which principals they name, verifies by each in turn and answers the one that
  verified as the signer; none named or none verifying is refused.
- `AllowedSigner` takes only a space or a tab as a blank, around a line and between its fields.
- Each has a test seen red on the code before and red again by a mutant: `ReconcileTest` retiring a key pinned under
  a quoted principal, `FollowedProjectsTest` unfollowing quoted, tab-parted and indented lines,
  `CommitVerifyCommandTest` deciding a person by the principals git finds and reading only a space or a tab as a blank,
  the commits also judged by `git verify-commit`, and `AllowedSignerTest` pinning no key for a line parted by another
  blank.
- The last three - a commit's own encoding, every `gpgsig` header appended, text after the armor's end or a reserved
  field - are left open and not measured: as the code reads, each refuses a commit git accepts and accepts none git
  refuses.
