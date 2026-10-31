# B340 — A Host Checks The Registry It Was Brought

**Status:** Review.
**Size:** M (operator, 2026-10-31) - grew from S (estimated 136 minutes from the median of S builds:
one verb over the registry rule, run as `verify` runs) when its security reading added the bundle boundary, the
first-parent rule for `--since` and the checks of the access file's projects.

**What must be true.** `porelo registry verify --repository <bundle> --host <host> --pinned <host admin fingerprint>
[--since <commit>] --json` checks a registry brought to the host as a bundle of the whole repository. Without network it
cannot ask the forge, so its anchor is the pinned host admin alone: the host's card and access file must be signed by
that key or a change of key from it, and a person's card is taken only for a login the host's access file names, against
the fingerprint it pins there, or a change of key from it ([B337](README.md#b337)). `owners.yml` and every other path
are not read. With `--since`, the commit the host applied last, a bundle whose main branch does not continue that
commit on its first-parent line exits 65, so no lock, revocation or change of key is rolled back by an older or forked registry. It answers
`{"commit", "host_card": {..., "signed_by"}, "admins", "projects": [{"name", "admin", "access_repository"}], "persons":
[{"login", "ssh", "expires", "revoked"}]}`, the host card checked as [B349](README.md#b349) does. It exits 65 as
`verify` does. `host_card.signed_by` is the `SHA256:` fingerprint of the key that signed the host card in force, so the
host moves its pin to it after a change of key. It needs no network, no home and no vault, so root's sync runs it in the
same isolated unit.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A bundle whose host files were signed by the pinned host admin is answered as above.
- A bundle with a host file signed by another key exits 65, naming commit and path.
- It answers the same with `HOME` unset, no vault and no route to any network.
- A card whose key is not the fingerprint the host's access file pins for that login is answered `revoked: true`.
- With `--since` a commit outside main's first-parent line, or a commit the bundle lacks, it exits 65 naming
  both; with `--since` the head itself, it answers.
- After a change of the host admin's key, `host_card.signed_by` names the new key.
- Seen to fail first: there is no `registry verify` today.

## Done

- `registry verify` is registered beside `card`; `RegistryVerifier` owns trust checks, `RegistryRepository` owns the
  private bare clone, and `RegistryAccess` reads strict SnakeYAML events. Picocli generates reflection metadata.
  JSON retains the specified fields; plain output remains available. Exit codes are 64 for bad options, 65 for
  untrusted state and 66 for an unreadable or nonbundle source. The existing host-card commands and types are
  unchanged.
- The source is a regular, nonsymlink v2 or v3 Git bundle. It is copied from an opened descriptor into the private
  directory; the copy is validated before Git runs, and Git receives only that private path. Cloning uses no
  checkout, hardlinks, inherited Git configuration or environment, remote helpers or network protocols. Only
  `refs/heads/main` selects registry state. The clone, copied bundle and signers file are removed on every exit;
  cleanup attempts every accessible path and reports traversal and deletion failures after continuing.
- The paths read are `hosts/HOST/card`, `hosts/HOST/access.yml`, and `people/LOGIN.card` for the named admins.
  `owners.yml` and unrelated content are not read. Project names match `[a-z0-9][a-z0-9-]{0,62}` and are unique
  after lowercasing. Access repositories pass `Repository.refuseAsOption`, reject remote-helper transports, and
  accept only `ssh://`, `https://` or `user@host:path`. Access YAML rejects aliases, anchors, tags, merge scalars,
  duplicate or unknown fields, multiple documents and controls.
- Every first-parent host change is compared with its own first parent and verified in the SSH `git` namespace
  against the key in force. An old-key-signed commit can introduce a host card signed by the new key. The final
  host card must verify against the resulting key. Every host-card refusal identifies its commit and path.
- `--since` is trusted applied state, expressed only as a full lowercase object name of 40 or 64 hex digits. It
  must identify a commit on main's first-parent line, including the head itself; a second-parent link is refused
  with both commits named. The applied host card must verify with the supplied pin, and only later first-parent
  changes are judged. Without `--since`, the initial pin judges the whole first-parent history. Save `commit` and
  `host_card.signed_by` together for the next synchronization.
- Person cards are checked with `PersonCardChain` before publishing `ssh` or `expires`. Missing, malformed,
  forged, unpinned and over-limit cards retain the login with empty fields and `revoked: true`. The person's
  `--full-history` query returns at most 65 entries to detect overflow; at most the chain's shared cap of 64
  history changes is read. A merged branch can provide an intermediate dual-signed card. There is no KRL input.
- One recursive output helper replaces every Unicode control, format, line separator and paragraph separator
  with `?` in both diagnostics and JSON, after signature and data checks.
- Tests: `RegistryVerifyCommandTest` (85) and `RegistryRepositoryTest` (2), beside the card tests. The security reading
  found three high faults (a merge leaving the applied line, `--since` resolved inside the bundle, a source that was no
  bundle) and six lesser ones; each fix was seen red by undoing it. Flags that a checked bundle cannot reach
  (`--no-replace-objects`, `core.hooksPath`) stay as a second wall and are not claimed by a test.
