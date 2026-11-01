# B363 — A Push Judged By Porelo When It Arrives

**Status:** Review. Accepted.
**Size:** M, proposed - one verb over the pre-receive input, the signers in force, the certifiers and the recorded
task certificates, with tests over a quarantined push.

**What must be true.** The pre-receive hook of the host's forge asks Porelo to judge every new commit of a push: `porelo
commit verify --repository <git-dir> --signers <file> --certifiers <file> [--krl <file>] [--certificates <file>]
[--json]`, the hook's `<old> <new> <ref>` lines on stdin. Every new commit, merges included, must carry an SSH signature
that holds now, at its arrival, whatever time the commit names: a person's by a key among the project's signers in force
([B341](README.md#b341)), a task's by its certificate from a certifier ([B342](README.md#b342), [B343](README.md#b343)),
or a task's plain key whose recorded certificate would itself be accepted; none revoked by the KRL. Any other commit is
refused. It reads the pushed objects through the hook's quarantine, runs without a home or the network, writes nothing
but the scratch files ssh-keygen reads, removed before it answers, judges every commit so all refusals are named at
once, and answers each verdict for the host to record.

It is part of sprint 6m, for the host's issue that judges each push when it arrives. The contract for the
host is `~/.porelo/handover/porelo/sprint-6m-host-task-contracts.md`, part C.

## Acceptance

- A push with an unsigned commit, a GPG signature, a key not in force, a revoked key, a task certificate expired at
  arrival though the commit is dated earlier, or a plain task key without a recorded certificate exits 65, naming each
  refused commit.
- A push of commits signed by a person in force and by a task's certificate exits 0; a deleted ref judges nothing.
- The commits are read from a quarantined push as git hands it to pre-receive.
- `doc/commands.md` and `doc/hosts.md` name the verb.

## Done

- `porelo commit verify --repository <git-dir> --signers <file> --certifiers <file> [--krl <file>] [--certificates
  <file>] [--json]` reads the hook's `<old> <new> <ref>` lines, at most 1 MiB, each id checked as 40 or 64 lowercase
  hex and each ref as `refs/...` before git sees them. The new commits of a ref are `git rev-list --not --all --not
  --end-of-options <new>`, merges included; a deleted ref brings none, and a commit two refs bring is judged once.
- Git runs from a chosen environment: `PATH`, `LC_ALL=C`, no system or global configuration, no replacements, no
  prompt, and `GIT_QUARANTINE_PATH`, `GIT_OBJECT_DIRECTORY` and `GIT_ALTERNATE_OBJECT_DIRECTORIES` passed on as the
  hook has them; nothing else of the account's environment reaches it, and no home, vault or network is used.
- A signature is split from its commit as the gate's arrival check splits it, and verified by `ssh-keygen -Y verify -n
  git` with `-Overify-time` at the arrival, never at the commit's date. A person's key must be a line of `--signers`
  that is neither retired (`valid-before`) nor `cert-authority`, as [B341](README.md#b341) reads the keys in force.
  A certificate must name exactly one principal `task:<project>:<task>` (`default` or a project name, and a task name),
  be valid now, and be issued by a `cert-authority` line in force of `--certifiers` whose principals match, by
  `ssh-keygen -Y match-principals` ([B342](README.md#b342)'s certifiers); a signature carrying it is verified against
  `--certifiers`. A plain key with no line in `--signers` is a task's only when a `--certificates` record for that key
  passes the same check ([B343](README.md#b343)'s records). The KRL is asked with `ssh-keygen -Q` of the person's key or
  the certificate line, which also covers the task key and the certifier's.
- `--certificates` reads one record per line: the JSON `porelo-host-person task --show` answers, of which only the
  `certificate` is read and checked again, or the certificate line alone; a line that is neither is 66.
- Exit 0 when every commit is accepted, 65 when any is refused, 64 for wrong use, 66 for a named file missing or
  unreadable, 70 when git or ssh-keygen cannot be run. stderr names each refused commit, `<commit> <reason>`; more
  than 10 000 new commits, or input that is not such lines, is refused whole as `porelo: <reason>`. `--json` answers
  `checked_at`, `accepted`, `commits` (`commit`, `ref`, `verdict`, `signer` with `kind`, `fingerprint`, `principal`,
  `certificate`, `signed_by`, or `null`, and `reason`) and a top-level `reason`.
- `CommitVerifyCommandTest` builds real repositories, keys and certificates with git and ssh-keygen, and pushes into a
  bare repository whose pre-receive hook runs the verb in its own JVM without a home.
- `doc/commands.md` and `doc/hosts.md` name the verb.
- A reading's findings fixed, each with a test seen red by a mutant: the certifiers' lines in force at the arrival - a
  `cert-authority` line without `valid-before`, its `valid-after` passed, its `namespaces` admitting `git` as ssh-keygen
  matches the pattern, negation included - are one list, written as the file `-Y verify` reads and the only lines
  `match-principals` is asked of, so a task's plain key with a recorded certificate holds the same certifier rules as a
  signature carrying the certificate; ssh-keygen runs with `TZ=UTC`, so a time without `Z` reads as UTC on both sides.
  A task principal's name is held to the container-name length `task start` holds it to, its project included. A
  record line of `--certificates` that is broken JSON is 66 and writes no failure log. ssh-keygen checking the KRL and
  the allowed-signers files runs from the same chosen environment as the verb's other calls, and ssh-keygen that
  cannot be run or scratch files that cannot be written are 70. An empty path is 64. A SHA-256 repository's commits
  are read by the `gpgsig-sha256` header git writes there. Every text of the JSON answer, a certificate's principal
  too, is printable. The scratch files go to a directory of their own under `TMPDIR`, removed before the answer, and
  the documents say so.
