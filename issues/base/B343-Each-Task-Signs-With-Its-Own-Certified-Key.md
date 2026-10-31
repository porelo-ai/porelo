# B343 — Each Task Signs With Its Own Certified Key

**Status:** Review.
**Size:** L (operator, 2026-10-31) - grew from M (estimated 90 minutes from the median of M builds: task keys held
outside the container, their certificates, the start's handshake and the gate's check) with the host's records, the
start on a host and renewal.

**What must be true.** Every task Porelo starts gets its own Ed25519 key, held by Porelo outside the task's container.
At the start, the host's Porelo hands the task key's public half to the starting person's Porelo, which certifies it
with the person's key through their local agent - the agent is never forwarded - as an SSH certificate valid for the
task's lifetime, naming the task, the person, and the project and repository where there are any; the start waits for
it. Porelo records the certificate on the host and serves it by the key's fingerprint (`porelo task certificate
<fingerprint> --json`), so a signature by the plain task key is traced to its certificate. Porelo signs the task's
commits with the key on request, so the agent never holds it, and destroys it when the task ends. A suffix of the
task's name from its key is [B354](README.md#b354).

A project accepts a task's commit when its certificate was issued by a key among the project's certifiers
([B342](README.md#b342)); a task with no project is accepted against its starting person's key alone. Commits are judged
by the time they arrive - their first accepted push to the host's forge, recorded with its verdict, or for a task
without a host forge the gate's own receipt - not by the commit's own time, which the signer sets. A task's container
never reaches a forge itself: the gate on the host passes its branch on, to the host's forge through `git.sock` with the
task's own forge token; the container gets neither `git.sock` nor `web.sock`, which signs in by the caller's account,
nor any token.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Done

- A task with a gate gets an Ed25519 key, made and held outside its container, and a certificate for it before its git is
  set up: principal `task:<project>:<task>` (`default` without a project), key id `porelo task <task> by <login>`, valid
  seven days. Its starter's own agent signs it, by a key of a person the project's verified access file admits; a task
  without a project by the first Ed25519 key the agent holds. What comes back is checked before anything is kept: the same
  key, exactly that principal, valid now, at most seven days, signed by a certifier.
- The key and certificate are kept in the vault as the task's own entries and go with the task. The certificate is also
  recorded by its key's fingerprint, kept after the task: `porelo task certificate <fp> [--json]` answers it, and on a
  host the host's own record (`porelo-host-person task --show`), whose answer must name the same key.
- The container's git signs through a shim at `/run/porelo/sign`, which asks the gate (`POST /porelo/sign`, behind the
  task's token, at most 1 MiB); the gate signs with the certificate, reading the key at each signature. The container
  never holds the key.
- The gate judges every commit a push makes new to the mirror with `ssh-keygen -Y verify` for the task's principal
  against the certifiers' `cert-authority` lines, at the time it arrives: a commit arriving after its certificate ended
  is refused, naming the end, however it is dated. A refused push leaves the ref and the upstream untouched. Each
  verdict is recorded as an `arrived` event.
- A start whose starter's agent holds no certifier's key starts as before, unsigned, and says `"certified": false` in
  `task list --json` and over Varlink; on a host whose persons start tasks it is refused with 65.
- `porelo task start TASK --host LOGIN@HOST` starts on the host over SSH with no forwarding: the host asks on one line
  for the certificate, and this machine certifies it with its own agent after checking an Ed25519 key, exactly the
  principal `task:<project>:<task>` of the start, the project a project's name (`[a-z0-9][a-z0-9-]{0,62}`, so no comma
  adds a principal), at most seven days, and certifiers the host names, each Ed25519 (none named is refused, never the
  agent's first key, except for a task without a project, as at a start here). A request that does not read, or a
  line cut at 16 KiB, is refused and answered, never shown; a refusal stays one when the host has gone. The host runs the same start less `--host`, built from what was
  parsed: each option once with each of its values as `--name=value`, a flag with its value (`--rm=false` stays
  false), then its own flags, then `--` and the task, each word quoted for the host's shell. Refused with 64 before
  the agent is asked: a login or host that begins with anything but a letter or digit, or holds brackets; a task not
  named, beginning with `@` or `-`, spelled as its container or not a task name; no or an empty `--project`;
  `--restarted`, `--set-env`, `--set NAME` without a value and `--set-file`, which the host would take from its own
  environment or files. ssh also gets `ForwardX11=no`; the host's output is shown cut to 16 KiB a line, its controls
  replaced. `porelo task certify TASK --host ... --project PROJECT` certifies the same key anew, by the same checks, and tells
  the host the project; `-p` without `--host` picks that project's task here, never another project's of the same
  name. A task without a project is certified again only by the key that certified it at its start, which its gate
  checks; a project's task whose access file names no certifier is refused with 65, never signed by the agent's first
  key, and started uncertified wherever it is asked for; no certificate is taken for such a project's task from any
  key. The host's task name is checked as a task's name and its end must lie ahead. A line is never cut between the halves of a character, and line and paragraph separators are replaced too.
- On a host (`/usr/local/sbin/porelo-host-person` present), the start hands the certificate to `porelo-host-person task`
  and keeps the forge token it answers in the vault; the gate passes the branch on as `porelo/<task>` by a
  plain `git push http://127.0.0.1:<git_port>/<project>/<repo>.git`, without force and without sudo, the port from
  `/etc/porelo-host/forge.conf` and the token in the push's environment for `porelo vault credential --env`, never in
  an argument or the URL. Git runs there from a chosen environment, without the account's or the system's git
  config, hooks or proxy, and with http as its only transport; task, repository and commit are checked before they
  reach git, and the token and its Basic form are masked in what git answers. What still counts is the account's own:
  the mirror's config and `~/.netrc`, which the task cannot write; removing the task calls `task --end`. The
  token stays in the vault and the gate. No code mounts the host's `git.sock` or `web.sock` into a container; no
  test guards that absence.
- The full suite runs green, 4498 tests and 2 skipped; every new check was seen red by mutation first, and nine narrow
  readings were answered.
- A host refusing a start it cannot certify says why, the project's access file naming no certifier or the card's key
  not in the agent, and cleans up what it started for it, the vault's broker first, as every refused start does.
- Not claimed: no test drives a host's refusal of an uncertified start, since only a host has its person program;
  the host names which of the person's agent keys certifies the task, among those it learned from ssh
  authentication; a host that answers falsely is not read back.

## Acceptance

- A task's commit is signed with its key, traced to its certificate by fingerprint, and accepted against the project's
  certifiers.
- A commit arriving after its certificate expired is refused, whatever date it carries.
- A task without a project gets a certificate from its starting person and its commits verify against that person's key.
- A task's container holds neither `git.sock`, `web.sock` nor a forge token; the gate passes its branch on with the
  task's own token, not the person's.
- Seen to fail first: today a task's commits carry no signature of its own.