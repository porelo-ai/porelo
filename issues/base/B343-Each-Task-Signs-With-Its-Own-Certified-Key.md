# B343 — Each Task Signs With Its Own Certified Key

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: task keys held outside the container, their
certificates, the start's handshake and the gate's check.

**What must be true.** Every task Porelo starts gets its own Ed25519 key, held by Porelo outside the task's container.
At the start, the host's Porelo hands the task key's public half to the starting person's Porelo, which certifies it
with the person's key through their local agent - the agent is never forwarded - as an SSH certificate valid for the
task's lifetime, naming the task, the person, and the project and repository where there are any; the start waits for
it. Porelo records the certificate on the host and serves it by the key's fingerprint (`porelo task certificate
<fingerprint> --json`), so a signature by the plain task key is traced to its certificate. Porelo signs the task's
commits with the key on request, so the agent never holds it, and destroys it when the task ends. A task's name carries
a short suffix from its key's fingerprint, unique per login.

A project accepts a task's commit when its certificate was issued by a key among the project's certifiers
([B342](README.md#b342)); a task with no project is accepted against its starting person's key alone. Commits are judged
by the time they arrive - their first accepted push to the host's forge, recorded with its verdict, or for a task
without a host forge the gate's own receipt - not by the commit's own time, which the signer sets. A task's container
never reaches a forge itself: the gate on the host passes its branch on, to the host's forge through `git.sock` with the
task's own forge token; the container gets neither `git.sock` nor `web.sock`, which signs in by the caller's account,
nor any token.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A task's commit is signed with its key, traced to its certificate by fingerprint, and accepted against the project's
  certifiers.
- A commit arriving after its certificate expired is refused, whatever date it carries.
- A task without a project gets a certificate from its starting person and its commits verify against that person's key.
- A task's container holds neither `git.sock`, `web.sock` nor a forge token; the gate passes its branch on with the
  task's own token, not the person's.
- Seen to fail first: today a task's commits carry no signature of its own.