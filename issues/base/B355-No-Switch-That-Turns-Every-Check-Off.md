# B355 — No Switch That Turns Every Check Off

**Status:** Accepted.
**Size:** S, proposed, plus the room's and the interface's parts - no `--insecure` is built; what is built is one
visible mark for what already runs unsigned or unverified, in `task list`, the interface, the gate's verdicts and the
room, read from state that exists.

**What must be true.** Should Porelo get an `--insecure` switch that turns off all checks, everywhere, on
request, for a developer who starts it briefly in the default project on their own machine without any
setup - given that working uncertified must always stay possible?

## The checks today

Read in the code on 2026-11-04. Host and room rows below are the earlier reports from their repositories,
which this checkout cannot establish; *planned* names work those reports described as still being built.
`b343:` identifies the earlier task-certificate reading, merged since.

**Signatures and the gate**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Push held until a person approves (`guarded`); `offline` forwards nothing | the upstream from unread agent work | the project, other persons | `gate/.../GateMode.java:13`, `GateMode.of` |
| Approved work merged as a commit signed by the approver; unsigned is not pushed | provenance of what reaches the upstream | the project | `gate/.../GitGate.java:1228`, `:1319` |
| Each arriving commit verified against the task's certificate, at arrival time | that a push is the task's own | the project, the host | `b343: gate/.../SignedArrivals.java`, verdicts recorded by `b343: apps/app-gate/.../ArrivalRecords.java:48` |

**Task certificates**

| Check | Protects | Whom | Where |
|---|---|---|---|
| One principal `task:<project>:<task>`, at most 7 days, signed by an admitted person's key | that a task acts for the person who started it | the project, other persons | `b343: apps/app-task/.../TaskCertification.java:23`, `:126`, `:141` |
| Current project members' keys certify tasks | who may vouch | the project | `TaskCertifying`, `ProjectMembers` |
| Local start without a certifier's key starts **unsigned**; a host refuses (65) | the host's forge and its persons | the host, other persons | `b343: .../TaskLaunch.java:1877`, `:1901` |

**Project and organization files**

`organization/git-tree/src/main/java/org/fuin/porelo/organization/gittree/ProjectFile.java` judges the newest
change to `project.yml` against current project admins' signing keys and remembers its accepted date.
It does not require signatures on every image, snippet or tool-settings file; only `project.yml` is judged.
This is the choice recorded in `doc/decisions.md` on 2026-11-02.

`organization/git-tree/src/main/java/org/fuin/porelo/organization/gittree/TreeWalk.java` judges the organization's
paths by their own rules, with the roles that held at each change. Host reads use `TreePin` and the pinned keys.
Manual `project follow`, its opt-outs, `project following`, `ConfigurationGate` and the `registry` commands are gone.

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

Every varlink server checks the kernel's peer uid (`SO_PEERCRED`), and both ends of the host vault's exchange do and
judge the directories above the socket.

**Container hardening**

| Check | Protects | Whom | Where |
|---|---|---|---|
| `--security-opt no-new-privileges`, `--cap-drop ALL`, `--pids-limit`, limits | the host from the agent | the person, the host | `runtime/.../ContainerSpec.java:232-256` |
| Seccomp: podman's default profile; Porelo sets none of its own | the kernel | the host | none in `ContainerSpec`; measured in `vault/.../KernelKeyring.java:548` |
| `--network private`, nft output chain `policy drop`, egress sets, DNS through Porelo's resolver | what the agent may reach | the person, the project | `ContainerSpec.java:263`, `shield/.../NftRuleset.java:190`, `hooks/.../NftHook.java` |
| Volumes relabelled `:Z` | SELinux separation of mounts | the host | `ContainerSpec.java:297` |

**The host (porelo-host, as its repository names them)**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Registry bundle signed by the pinned host admin; host card; KRL and revocations | who the host serves | all its persons | *planned* (the host's registry sync) |
| Login rule, one key per person, lock, pending lock, purge | admission | all its persons | `roles/porelo/files/porelo-host-person` |
| Access file verified per project; sync only by that project's admin; roles admin\|developer | a project from a non-admin | the project | `porelo-host-person` (`sync`) |
| Task certificate: current key, one principal, valid now; record never replaced; task token and its sweep | that a task is its person's | other persons | *planned* (the host's task records) |
| `web.sock` (peer's login), `git.sock` (token always), runner gate, no TLS/CA | the forge | all its persons | *planned* (the forge's gates) |
| sudo helpers: caller uid and name, membership, not locked; root's files plain, root-owned, not other-writable; tokens on stdin | root and other accounts | the host | `roles/forge/files/porelo-host-project:93`, `:107`; `porelo-host-push`; `porelo-host-person:343` |
| SSH only, one key per person, `porelo-people`, forwarding only to the gates' sockets | the host's only way in | the host | *planned* (SSH-only access) |

**The room (porelo-message-matrix, as its repository names them)**

| Check | Protects | Whom | Where |
|---|---|---|---|
| Gate: caller by `SO_PEERCRED`, `porelo-people` from `/etc/group` per request, login rule, expelled logins | one person acting as another | other persons | `Gate.java:97`, `:103`, `Expel.java:17` |
| Tokens bound by whoami; `/login` and `/register` for own names; only `/_matrix/client` and `/_matrix/media` | the homeserver | other persons | `Gate.java:29-30` |
| Aliases `porelo-<project>` only by that project's admins; login tokens single use, 2 min; request limits | a project's room | the project | `Gate.java:548` |
| SSO redirect target rule (weak today) | sign-in | other persons | as porelo-message-matrix names it |
| Transport: loopback homeserver must be this uid's; `--loopback-only` | the person's own messages | the person | `Listener.java:44`, `Lifecycle.java:1702` |
| Person rule on a central homeserver; name suffixes never cross logins | whose words are whose | other persons | `Login.java:58`, `Send.java:149`, `Person.java:52` |
| `ca_certificate` refused, absolute `unix:` paths, `max_bytes` 10 KiB | hygiene | the person | `SettingsCheck.java:118`, `Describe.java:22` |

## What "off" would mean

| Check | Turning it off | Honourable on own machine, own data? | Never honoured |
|---|---|---|---|
| Gate review | agent pushes reach the upstream unread | already possible: security class `online` | a shared project's upstream another person relies on |
| Approver's signed merge | unsigned work upstream | no gain: the approver signs with their own git key | anywhere others verify the upstream |
| Arrival judging | any commit accepted as the task's | already off where a task is uncertified | on a host; in a project with certifiers |
| Task certificate | task unsigned | **already the case** locally without a certifier's key | a host
(`TaskLaunch.java:2045` stays) |
| Project file signature | who sets the firewall | no manual-follow opt-out exists | shared project settings |
| Organization verification | the host takes unsigned persons | no: it is a host's | always |
| Moderation, message signatures | a task's words reach others unread | no: the words go to other persons | always; `off` already keeps the filter |
| Daemon socket mode, vault sockets, SELinux type | another local user or task reaches the daemon or a token | no: the cost falls on other tasks and users | always |
| Container hardening, egress | the agent gets privileges or the open network | egress: yes, via `shield egress` and clearance `allow`; privileges: no | container isolation from the host, always |
| Host checks (all) | one person's switch weakens all persons on the host | no | always (porelo-host: "a host's checks are switched off only by not running a host") |
| Room gate (A), person rule, suffixes, `ensureOwn` | one person speaks as another | no | always (porelo-message-matrix: "no switch reaches the transport or the gate") |

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
- **A visible mark.** `project following` and its `verified: false` answer are gone. `task list` and
  `task status --json` still lack the requested marks, and `ArrivalRecords` still records bare `accepted`.
  The `unsigned` / `unverified` promise below remains; its manual-follow acceptance case has no current command.
- **Per-check flags that exist:** security class `online` (no review); `shield egress --add-set`
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

Size **S** - read-only state (the project verification outcome, the task's recorded certificate or
its absence) carried into three outputs and one record line; no new check, no new option.
`FollowedProjects.Followed.unverified()` is gone; which current project state carries the same promise is unsettled.

## Acceptance

- A task started in `default` without a certifier's key starts, and `task list` and `task status --json`
  name it `unsigned`; one with a certificate does not carry the mark.
- A task of a project followed `--unverified` shows `unverified` in `task list` and the interface.
- `ArrivalRecords` writes `accepted, unsigned` for an uncertified task's push, never a bare `accepted`.
- A start on a host without a certificate is still refused with 65 (`TaskLaunch.java:2045`), seen to fail
  if the mark is mistaken for permission.
- `porelo --insecure` and any `insecure` key are refused as unknown, seen in a test.
- A message from an uncertified task, or from a task of an unverified project, shows the mark in its line and the
  task's display name in the room; another person's Porelo marks it from its own check of signature and
  certificate, and marks a message whose signature it cannot check the same way, whatever the sender wrote.

## Decisions

- No `--insecure`; the visible mark is built instead.
- `default` stays `guarded`, with no class of the person's choosing.
- The mark also reaches the room: other persons see that a message came from an uncertified task or an unverified
  project. What it takes in the transport and the interface is the room's and the
  interface's part of this issue.

## The mark, place by place

- **On this machine:** `task list` and `task status --json` answer the task's `certified` (true or false, as
  the task's certificate answers it; the interface's proposed `signing` is this field), beside the project's
  `verified`; an absent value shows as `unsigned`, never as certified. The gate's answer for a waiting push carries its arrival verdict as `ArrivalRecords` writes it
  (`accepted` or `accepted, unsigned`), so the review shows it before an approval.
- **In the room, for persons reading in any client:** the sending Porelo puts the mark into the line it already
  makes (`send --shown`) and into the task's display name (`rename --display`, set again when the state changes).
  The transport carries both unread; nothing changes there. A machine-readable `send --mark` in the transport is an
  optional XS of the room's, built only when it is wanted.
- **In another person's interface:** the mark is the receiving Porelo's own verdict, from the message's signature
  and the task's certificate it checks itself. A mark the sender writes is the sender's
  word: a sender that lies leaves it out, so its absence never reads as certified, and the interface shows nothing
  it did not check. The mark is always a checker's verdict - the receiving Porelo's, the gate's or the host's -
  never the sender's own content. The talk event Porelo hands the interface carries it as `mark` (`unsigned`,
  `unverified` or empty).
- No per-person config file: every opt-out stays a flag on its command.
