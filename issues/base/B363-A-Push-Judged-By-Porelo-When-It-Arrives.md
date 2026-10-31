# B363 — A Push Judged By Porelo When It Arrives

**Status:** Accepted (operator, 2026-10-31).
**Size:** M, proposed - one verb over the pre-receive input, the signers in force, the certifiers and the recorded
task certificates, with tests over a quarantined push.

**What must be true.** The pre-receive hook of the host's forge asks Porelo to judge every new commit of a push:
`porelo commit verify --repository <git-dir> --signers <file> --certifiers <file> [--krl <file>] [--certificates
<file>] [--json]`, the hook's `<old> <new> <ref>` lines on stdin. Every new commit, merges included, must carry an SSH
signature that holds now, at its arrival, whatever time the commit names: a person's by a key among the project's
signers in force ([B341](README.md#b341)), a task's by its certificate from a certifier ([B342](README.md#b342),
[B343](README.md#b343)), or a task's plain key whose recorded certificate would itself be accepted; none revoked by
the KRL. Any other commit is refused. It reads the pushed objects through the hook's quarantine, runs without a home
or the network, writes nothing, judges every commit so all refusals are named at once, and answers each verdict for
the host to record.

The operator took this into sprint 6m on 2026-10-31 at the coordinator's relay, for the host's issue that judges each
push when it arrives. The contract handed to Agent Machines is
`~/.porelo/handover/porelo/sprint-6m-host-task-contracts.md`, part C.

## Acceptance

- A push with an unsigned commit, a GPG signature, a key not in force, a revoked key, a task certificate expired at
  arrival though the commit is dated earlier, or a plain task key without a recorded certificate exits 65, naming each
  refused commit.
- A push of commits signed by a person in force and by a task's certificate exits 0; a deleted ref judges nothing.
- The commits are read from a quarantined push as git hands it to pre-receive.
- `doc/commands.md` and `doc/hosts.md` name the verb.
