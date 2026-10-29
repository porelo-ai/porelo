# B332 — A Follow Lists The Keys It Pinned

**Status:** Estimated.
**Size:** XS - one field in `following --json`, read from the pinned signers file that `signed_by` already reads;
a test and a line in `doc/hosts.md` (XS median 26.5 min, n=6).

**What must be true.** `porelo project following --json` names, for each follow, the keys in force for it as keys
(`ssh-ed25519 AAAA...`), in a new field `signed_by_keys` beside `signed_by`, which keeps its fingerprints. A program
that checks a newer commit of the project with `porelo project verify --since <followed commit>` then has the keys
`verify` takes, after a handover too.

Asked by Agent Machines for porelo-host's bring (2026-10-29): the second bring checks with `--since` the followed
commit, and [B311](README.md#b311)'s `verify` takes the keys in force at that commit, each the key itself, never a
fingerprint. The host has the registration key from the person's machine file, which is in force only until the first
handover. After a change of signers, the next bring is refused with 65.

## Acceptance

- `following --json` carries `signed_by_keys`: the keys this account pinned for the project, in the order pinned,
  each as `<type> <base64>` without a comment.
- A follow walked through a handover lists the keys in force after it.
- `signed_by` is unchanged, and `doc/hosts.md` names the new field.

## Plan

- `PinnedSigners` gains a reader of the keys themselves per project, `<type> <base64>` in pin order, beside the
  fingerprints it gives today.
- `ProjectFollowingCommand.json` puts `signed_by_keys` from it next to `signed_by`.
- A test follows a project, hands it over to another key, and finds both lists in step; `doc/hosts.md` names the field.
