# B362 — A Host Checks A Task Certificate Before It Records It

**Status:** Review. Accepted.
**Size:** S, proposed - one verb over the certificate checks [B343](README.md#b343) already makes, with its tests.

**What must be true.** The host's helper for its persons, run as root at a task's start, has Porelo judge the task's
certificate before it writes anything: `porelo task check-certificate --task <task> --login <login> --keys <file>
[--krl <file>] [--json]`, the certificate on stdin. It is accepted only when it is one Ed25519 user certificate of at
most 16 KiB, signed by one of the person's keys in force that the host passes, neither key revoked by the KRL, with
exactly the principal `task:<project>:<task>` of that task, the key id `porelo task <task> by <login>`, valid now and
for at most seven days. It runs without a home, a vault or the network, writes nothing, and answers the task, the
project, the task key's fingerprint, the signer and the validity. Porelo's caller of the helper, built in
[B343](README.md#b343), is written down as the host's contract and drops one trailing newline of the token it takes.

It is part of sprint 6m, for the host's issue that records a task and gives it its forge token. The contract for the
host is `~/.porelo/handover/porelo/sprint-6m-host-task-contracts.md`, parts A and B.

## Done

- `porelo task check-certificate` reads one line of at most 16 KiB from standard input, ending in at most one line
  end, and refuses a longer one, a second line or none before anything parses it; it reads no more than that much.
  It needs no home, vault or network and writes nothing: the revocation list is asked by `ssh-keygen -Q -f <krl>
  /dev/stdin`, each key handed on its input, so no key file is made.
- It accepts only what the start issues: signed by exactly one of `--keys`' lines, neither that key nor the task's
  revoked, exactly one principal `task:<project>:<task>` of that task and a project name or `default`, the key id of
  that task and login, no critical option and exactly the five extensions `ssh-keygen` grants by default, each
  empty. Exit 64 for a task or login that is not one, 66 for a named file missing or unreadable, the list included.
- Its validity is the start's own rule, valid now or within five minutes and valid before at most seven days and
  five minutes from now by the host's clock, moved into `TaskCertification.checkValidity`, which both call; the
  start's certificates span seven days and a minute, so a strict seven-day span would have refused every one.
- The line must be printable ASCII; a carriage return, a NUL or any byte beyond ASCII is refused, since the host
  keeps the bytes as they came. A principal is read only when it is longer than `task:` and `:<task>` together, and
  a project's task must fit the container name the start would give it; a login Porelo keeps for itself (`porelo`,
  `porelo-...`) is wrong use, as is a missing option (64, not picocli's 2).
- `SshCertificate` names its extensions with their data, and refuses one named twice.
- `HostPerson.start` keeps the token without one trailing line end. `doc/hosts.md` writes down the helper's
  contract, the start, end and show calls with the check, and `doc/commands.md` names the verb.

## Acceptance

- A certificate signed by a key not among `--keys`, by a key the KRL revokes, of another task or login, naming two
  principals, expired, valid for more than seven days, or longer than 16 KiB is refused with 65 and one line naming
  the rule, before anything is written.
- An accepted one answers exit 0 and, with `--json`, the fields of the contract.
- The start on a host keeps the token without the helper's trailing newline.
- `doc/commands.md` and `doc/hosts.md` name the verb and the caller's contract.
