# B362 — A Host Checks A Task Certificate Before It Records It

**Status:** Accepted (operator, 2026-10-31).
**Size:** S, proposed - one verb over the certificate checks [B343](README.md#b343) already makes, with its tests.

**What must be true.** The host's helper for its persons, run as root at a task's start, has Porelo judge the task's
certificate before it writes anything: `porelo task check-certificate --task <task> --login <login> --keys <file>
[--krl <file>] [--json]`, the certificate on stdin. It is accepted only when it is one Ed25519 user certificate of at
most 16 KiB, signed by one of the person's keys in force that the host passes, neither key revoked by the KRL, with
exactly the principal `task:<project>:<task>` of that task, the key id `porelo task <task> by <login>`, valid now and
for at most seven days. It runs without a home, a vault or the network, writes nothing, and answers the task, the
project, the task key's fingerprint, the signer and the validity. Porelo's caller of the helper, built in
[B343](README.md#b343), is written down as the host's contract and drops one trailing newline of the token it takes.

The operator took this into sprint 6m on 2026-10-31 at the coordinator's relay, for the host's issue that records a
task and gives it its forge token. The contract handed to Agent Machines is
`~/.porelo/handover/porelo/sprint-6m-host-task-contracts.md`, parts A and B.

## Acceptance

- A certificate signed by a key not among `--keys`, by a key the KRL revokes, of another task or login, naming two
  principals, expired, valid for more than seven days, or longer than 16 KiB is refused with 65 and one line naming
  the rule, before anything is written.
- An accepted one answers exit 0 and, with `--json`, the fields of the contract.
- The start on a host keeps the token without the helper's trailing newline.
- `doc/commands.md` and `doc/hosts.md` name the verb and the caller's contract.
