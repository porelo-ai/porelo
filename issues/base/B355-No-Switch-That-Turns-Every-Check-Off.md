# B355 — No Switch That Turns Every Check Off

**Status:** Idea.
**Size:** S, proposed - no `--insecure` is built; what is built is one visible mark for what already runs
unsigned or unverified, in `task list`, the interface and the gate's verdicts, read from state that exists.

**What must be true.** Should Porelo get an `--insecure` switch that turns off all checks, everywhere, on
request, for a developer who starts it briefly in the default project on their own machine without any
setup - given that working uncertified must always stay possible?

## The checks today

Paths are in `/home/michi/git/porelo` (core, `main`) unless named; `b343:` is the worktree
`porelo-core-b343` ([B343](README.md#b343), not merged). Host and room rows are as their owners gave them,
with the places found by grep; *planned* names host work still being built.

**Signatures and the gate**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Push held until a person approves (`guarded`); `offline` forwards nothing | the upstream from unread agent work | the project, other persons | `gate/.../GateMode.java:13`, `GateMode.of` |
| Approved work merged as a commit signed by the approver; unsigned is not pushed | provenance of what reaches the upstream | the project | `gate/.../GitGate.java:1228`, `:1319` |
| Each arriving commit verified against the task's certificate, at arrival time | that a push is the task's own | the project, the host | `b343: gate/.../SignedArrivals.java`, verdicts recorded by `b343: apps/app-gate/.../ArrivalRecords.java:48` |

**Task certificates** ([B343](README.md#b343))

| Check | Protects | Whom | Where |
|---|---|---|---|
| One principal `task:<project>:<task>`, at most 7 days, signed by an admitted person's key | that a task acts for the person who started it | the project, other persons | `b343: apps/app-task/.../TaskCertification.java:23`, `:126`, `:141` |
| Certifiers are the access file's admitted persons; none without a verified access commit | who may vouch for a task | the project | `b343: .../TaskCertifying.java:33` |
| Local start without a certifier's key starts **unsigned**; a host refuses (65) | the host's forge and its persons | the host, other persons | `b343: .../TaskLaunch.java:1877`, `:1901` |

**Project file and access file** (`project follow`, `project verify`, the follow's tick)

| Check | Protects | Whom | Where |
|---|---|---|---|
| A configuration commit taken only when signed by a pinned `allowed_signers` key (`git verify-commit`) | the firewall and settings every follower applies | the person themselves, the project | `apps/app-base/.../ConfigurationGate.java` |
| Retired keys (`valid-before=`) never authorize a new commit | against a stolen old key | the project | `AllowedSigner.java:88`, `ConfigurationGate.java:171` |
| Protected files (`project.yml`, `machine-signers`, `porelo.yml`, the image, ...) only from a signed commit | what a machine consumes | the person, the project | `apps/app-project/.../ProtectedFiles.java:35` |
| New commit must descend from the one in force | against a rewritten history | the person | `ProjectFollowCommand.java:40` (`--accept-rewrite` opts out) |
| Messaging signers taken only from verified follows | who may speak for a peer | other persons | `apps/app-messaging/.../KnownPeers.java:51` |

**Registry**

| Check | Protects | Whom | Where |
|---|---|---|---|
| `registry verify --repository --host --pinned --since`: bundle signed by the pinned host admin, head descends | the host's persons, keys and projects | the host, all its persons | `apps/app-vault/.../RegistryVerifyCommand.java:29` |
| Path-by-path signers, `owners.yml`, forge-listed keys ([B339](README.md#b339)) | who is who in the organisation | other persons | Idea/Implementing; not found in `main` |

**Room admission and messages (core side)**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Moderation per peer: `prompt`, `allow`, `deny`, `off`; `off` does **not** switch the filter off | other persons from a task's words | other persons | `apps/app-messaging/.../Moderation.java:40` |
| Mailbox chain and signatures verified | that history was not edited | the person, other persons | `MessageVerify.java:28` |

**Vault and sockets**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Daemon/varlink socket owner-only (`rwx------`) | the daemon from other local users | the person | `wire/.../varlink/VarlinkServer.java:112` |
| Vault proxy and ssh-agent socket: permissive socket in a `0700` directory mounted into one container | a credential from other tasks | the person, other tasks | `supervisor/.../VaultProxy.java:383`, `vault/.../SshAgentServer.java:73` |
| SELinux type `porelo_socket`: containers may only `connectto`, never bind one | one task's token from another | the person, other tasks | `selinux/porelo_socket.te` |
| No socket taken from a live process | availability | the person | `wire/.../LiveSocket.java:39` |

On `main` the daemon socket relies on its file mode alone; [B345](README.md#b345), about to be handed over,
adds the kernel's peer uid (`SO_PEERCRED`) on every varlink server and on both ends of the host vault's
exchange, and judges the directories above the socket.

**Container hardening**

| Check | Protects | Whom | Where |
|---|---|---|---|
| `--security-opt no-new-privileges`, `--cap-drop ALL`, `--pids-limit`, limits | the host from the agent | the person, the host | `runtime/.../ContainerSpec.java:232-256` |
| Seccomp: podman's default profile; Porelo sets none of its own | the kernel | the host | none in `ContainerSpec`; measured in `vault/.../KernelKeyring.java:548` |
| `--network private`, nft output chain `policy drop`, egress sets, DNS through Porelo's resolver | what the agent may reach | the person, the project | `ContainerSpec.java:263`, `shield/.../NftRuleset.java:190`, `hooks/.../NftHook.java` |
| Volumes relabelled `:Z` | SELinux separation of mounts | the host | `ContainerSpec.java:297` |

**The host (porelo-host; named by Machines)**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Registry bundle signed by the pinned host admin; host card; KRL and revocations | who the host serves | all its persons | *planned* (the host's registry sync) |
| Login rule, one key per person, lock, pending lock, purge | admission | all its persons | `roles/porelo/files/porelo-host-person` |
| Access file verified per project; sync only by that project's admin; roles admin\|developer | a project from a non-admin | the project | `porelo-host-person` (`sync`) |
| Task certificate: current key, one principal, valid now; record never replaced; task token and its sweep | that a task is its person's | other persons | *planned* (the host's task records) |
| `web.sock` (peer's login), `git.sock` (token always), runner gate, no TLS/CA | the forge | all its persons | *planned* (the forge's gates) |
| sudo helpers: caller uid and name, membership, not locked; root's files plain, root-owned, not other-writable; tokens on stdin | root and other accounts | the host | `roles/forge/files/porelo-host-project:93`, `:107`; `porelo-host-push`; `porelo-host-person:343` |
| SSH only, one key per person, `porelo-people`, forwarding only to the gates' sockets | the host's only way in | the host | *planned* (SSH-only access) |

**The room (porelo-message-matrix; named by Matrix)**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Gate: caller by `SO_PEERCRED`, `porelo-people` from `/etc/group` per request, login rule, expelled logins | one person acting as another | other persons | `Gate.java:97`, `:103`, `Expel.java:17` |
| Tokens bound by whoami; `/login` and `/register` for own names; only `/_matrix/client` and `/_matrix/media` | the homeserver | other persons | `Gate.java:29-30` |
| Aliases `porelo-<project>` only by that project's admins; login tokens single use, 2 min; request limits | a project's room | the project | `Gate.java:548` |
| SSO redirect target rule (weak today) | sign-in | other persons | as named by Matrix |
| Transport: loopback homeserver must be this uid's; `--loopback-only` | the person's own messages | the person | `Listener.java:44`, `Lifecycle.java:1702` |
| Person rule on a central homeserver; name suffixes never cross logins | whose words are whose | other persons | `Login.java:58`, `Send.java:149`, `Person.java:52` |
| `ca_certificate` refused, absolute `unix:` paths, `max_bytes` 10 KiB | hygiene | the person | `SettingsCheck.java:118`, `Describe.java:22` |

## What "off" would mean

| Check | Turning it off | Honourable on own machine, own data? | Never honoured |
|---|---|---|---|
| Gate review | agent pushes reach the upstream unread | already possible: security class `online` | a shared project's upstream another person relies on |
| Approver's signed merge | unsigned work upstream | no gain: the approver signs with their own git key | anywhere others verify the upstream |
| Arrival judging | any commit accepted as the task's | already off where a task is uncertified | on a host; in a project with certifiers |
| Task certificate | task unsigned | **already the case** locally without a certifier's key | a host (`TaskLaunch.java:1901` stays) |
| Follow signature, protected files, retired keys, rewrite | anyone who can push sets the firewall | yes, per follow: `--unverified`, `--accept-rewrite` exist | a project others sign; never as a global default |
| Registry verify | the host takes unsigned persons | no: it is a host's | always |
| Moderation, message signatures | a task's words reach others unread | no: the words go to other persons | always; `off` already keeps the filter |
| Daemon socket mode, vault sockets, SELinux type | another local user or task reaches the daemon or a token | no: the cost falls on other tasks and users | always |
| Container hardening, egress | the agent gets privileges or the open network | egress: yes, via `shield egress` and clearance `allow`; privileges: no | container isolation from the host, always |
| Host checks (all) | one person's switch weakens all persons on the host | no | always (Machines: "a host's checks are switched off only by not running a host") |
| Room gate (A), person rule, suffixes, `ensureOwn` | one person speaks as another | no | always (Matrix: "no switch reaches the transport or the gate") |

What is left for a global switch is only what already has a per-check, visible opt-out. Everything else
either protects another person or the host, where one person's switch must never decide.

## Alternatives

- **The default project as it is.** Measured: `default` needs no repository, signature, follow or access
  file (`apps/app-model/.../DefaultProject.java:19-23`); it has no access, so no certifiers and the task
  starts unsigned (`b343: TaskCertifying.java:33`, `TaskLaunch.java:1877`); its gate is `guarded`, review
  by `porelo gate approve` in the local checkout (`doc/getting-started.md:355`). The developer's case is
  served today without a switch; what it still costs is the gate review and the egress default.
- **A per-project setting in the person's own config**, never in a project file others sign. No per-person
  config file was found in core today; it would be new. It adds a second place that decides what a
  project's own file decides, and `default`'s settings are fixed by design (`DefaultProject.java:21`).
- **A visible mark.** `porelo project following` already says `unverified` and answers `verified: false`
  (`ProjectFollowingCommand.java:62`, `:126`); `task list` (`TaskListCommand.java`) shows neither an
  unverified project nor an uncertified task, and the gate's arrival records say only `accepted`. One
  mark - `unsigned` / `unverified` - in `task list`, `task status --json`, the interface and
  `ArrivalRecords` makes the weaker state seen without adding one.
- **Per-check flags that exist:** `project follow --unverified` and `--accept-rewrite`
  (`ProjectFollowCommand.java:40`, `:47`); security class `online` (no review); `shield egress --add-set`
  / `--add-domain`; `task clearance TASK allow|off`; moderation `allow|off`; `task remove --force`,
  `--yes`, `--no-input`. Each is narrow, named and reported.
- **Doing nothing.** Uncertified local work already runs; the risk is that it runs **unmarked**.

## Recommendation

Do not build `--insecure`. Every check it could honour has a narrow opt-out already, the default project
already runs unsigned and unverified without setup, and every other check protects another person, a
host or the host kernel, where a single person's switch must not reach - the owners of the host and the
room say the same of theirs. Build instead the mark: an uncertified task and an unverified project are
named as such in `task list`, `task status --json`, the interface and each gate verdict, and
`TaskLaunch`'s local unsigned start stays as it is.

Size **S** - read-only state (`FollowedProjects.Followed.unverified()`, the task's recorded certificate or
its absence) carried into three outputs and one record line; no new check, no new option.

## Acceptance

- A task started in `default` without a certifier's key starts, and `task list` and `task status --json`
  name it `unsigned`; one with a certificate does not carry the mark.
- A task of a project followed `--unverified` shows `unverified` in `task list` and the interface.
- `ArrivalRecords` writes `accepted, unsigned` for an uncertified task's push, never a bare `accepted`.
- A start on a host without a certificate is still refused with 65 (`TaskLaunch.java:1901`), seen to fail
  if the mark is mistaken for permission.
- `porelo --insecure` and any `insecure` key are refused as unknown, seen in a test.

## Open questions

- Is the gate review in `default` part of what the developer wants to avoid? If so, the answer is a
  person-chosen `online`-like class for `default`, not a switch - and `default`'s fixed settings change.
- Should the mark also reach the room, so other persons see a message came from an uncertified task?
- Is a per-person config file wanted at all, or does every opt-out stay a flag on its command?
