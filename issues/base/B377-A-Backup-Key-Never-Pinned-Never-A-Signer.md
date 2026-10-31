# B377 — A Backup Key Never Pinned, Never A Signer

**Status:** Idea.
**Size:** L, proposed - the follow's unchanged tip and signer change, a commit declaring its own signer a backup key,
host files in a trust record, revoked keys on every signing path, a repeated `--id` judged at its own commit, the kept
key file, typed reasons and URLs without userinfo, each with its test.

**What must be true.** What [B370](README.md#b370) states holds on every path: a backup key replaces its one key and
nothing else, is never pinned, active or retired, and no revoked key signs. A second reading of the fix of
[B370](README.md#b370) on 2026-10-31 found twelve places where this does not hold yet:

- A follow with `--signed-by <backup key>` on an unchanged tip returns before the handover is staged, so the backup
  key is pinned and an `access.yml` commit it signs is taken.
- A commit that declares its own signer a backup key is not judged as one: only the commit in force, the rewrite and
  the parent are asked, not the commit itself.
- A trust record holding `hosts/<host>/card` or `hosts/<host>/access.yml` placed by a host admin's backup key is taken.
- A follow with `--signed-by <backup key>` across a signer change writes the backup key as a retired signer.
- `identity backup-key --host --out` and `registry file owners` may sign with a key `revoked.krl` revokes.
- `project access --id` and a follow on an unchanged tip accept a signer a registry read here revokes.
- A repeated `--use --id` for a registry or host answers a commit an ordinary owner signed, not the backup key; the
  reading holds this against the documented rule, where the fix had declined it.
- A repeated `--id` for a registry or host refuses once a later commit touches `owners.yml`, though `--registry` and
  every `--host` share that file; either it is judged at its own commit or the limit is documented.
- A repeated `--id` for a registry or host does not check the caller's, the backup's or the replacement's key against
  revocations, where `--project` does.
- A key file is removed when reading `HEAD` after the filing fails with a command error, though its public half may
  be filed.
- `base-moved` and `not-signer` reach `--json` without their reason on two git failures.
- `identity backup-key --project` prints the project's URL with its userinfo in a refusal.

Each is answered by a test that fails today, or, for the repeated `--id` across scopes, by the documented rule.
