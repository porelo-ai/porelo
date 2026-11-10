# [B589](README.md#b589) — The Forge Knows The Key That Signed A Commit

**Status:** Analyzed. Filed 2026-11-10 under ST290, from the fourth move.
**Size:** L - measure certificate verification before choosing the registration design, then reconcile person and task keys with forge accounts.
**Blocked by:** [B550](README.md#b550), [B547](README.md#b547).

**What must be true.** A task's or person's signed commit on the host's forge shows a verified signature against the
account used for its push, while a commit signed by an unrelated key does not. Before implementing registration,
measure what the host and central forges accept for the task's certified signing key and record any limitation.

## Plan

Read in the code: `apps/app-task`'s `TaskSigning.gitEnvironment` sets SSH signing with the certificate as
`user.signingkey` and sends signing through the gate. `host/`'s `HostTasks.start` validates and records that certificate
with `HostCertificate.entry`, then issues the task token for `porelo-<login>`, not a separate account per task.
`FollowPeople.forgeAdmit` creates `<login>` and `porelo-<login>`; `ProjectRuntime.Session` provides the administrative
forge client. Neither that admission nor task start registers a signing key.

- First measure the installed Forgejo API's SSH/GPG key registration and commit-verification result with an OpenSSH
  certificate and with its underlying plain Ed25519 key. Use an actual certificate-signed git commit from the task
  signing path: accepting a key at registration alone proves nothing. Measure the central forge's corresponding
  signing-key API against the same commit before building central registration; record supported formats, attribution,
  required rights and limitations in `doc/hosts.md`.
- After that measurement, add bounded, idempotent key reconciliation beside `FollowPeople.forgeAdmit`, through
  `ProjectRuntime.Session`. Register the admitted person's current card key on `<login>`, and the recorded task keys
  on `porelo-<login>`, using the representation that actually verifies the unchanged certified signature. Read task
  certificates from `HostTasks`' private records, never from a workspace or a task's claim.
- Reconcile newly recorded tasks on the next follow and preserve the existing gate's certificate, principal,
  revocation and validity checks. Register only keys belonging to the admitted account; retry a failed registration
  on a later follow and report it. Repeated passes must not accumulate duplicate keys or remove unrelated keys.
- If neither representation verifies the existing signature, stop before changing signing or rewriting commits and
  revise this plan with the measured limitation. Do not promise that merely uploading a plain key makes a
  certificate-signed commit verified. Central registration is limited to what the measurement proves possible.
- Red first: a scripted forge in the host follow tests has an admitted person's card and a recorded task certificate;
  the follow registers each on the correct account, a repeated pass adds nothing, an unrelated key is refused and a
  forge failure leaves a reported retry. The real-forge acceptance then proves the signature-verification result.

## Acceptance

- A person commit and a task's certificate-signed commit show verified on the host forge under their respective push
  accounts. An unrelated signing key does not; registration must not weaken the push judge's certificate checks.
- The central measurement records whether the same unchanged commit can be verified there, with an observed refusal
  where its key format is unsupported. No unsupported central verification is reported as successful.

## To be checked

- Certificate versus plain-key registration and verification, duplicate-key ownership rules, API scopes and the
  treatment of expired certificates and historical verification on each forge.
