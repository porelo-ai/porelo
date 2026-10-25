# B234 — The Host's Authority In A Task's Trust Store

**Status:** Review: published since `0.5.1~snapshot.329`; green on the VM in its acceptance suite.
**Size:** M - `runtime` (`ContainerSpec`), `app-base` (`TaskRunner`, `TaskCertificates`, `PoreloPaths`),
`app-model` (`TaskState`), `app-task` (`TaskControl`, `TaskLaunch`), docs and an acceptance scenario.

**What must be true.** A task trusts a certificate authority the host names, so it reaches a service on the host
served under that authority's certificate without a project putting the certificate into its image.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) serves its
registry over TLS with a certificate authority made on the host. `~/.config/porelo/ca-certificates.pem` is read only
by Porelo's own clients on the host (the broker, a token purchase, `vault authorize`); nothing puts it into a task's
trust store, and the image's snippet is per project, not per host.

## Plan

1. `PoreloPaths` names `~/.config/porelo/task-ca-certificates.pem` beside the host clients' `ca-certificates.pem`.
   The task file holds only the host's own public authority certificates, in PEM, owned by the account, mode 0600.
   Host-client trust never enters a task; the two files may hold different authorities.
2. Before a new container is created, `TaskCertificates.prepare` refuses nonregular files, links, another owner,
   any group/other permission (`mode & 077`), malformed or empty X.509 PEM, and content other than certificates.
   Parsing uses `TrustedCertificates.context`; refusals name the configured file and the fix, including `chmod 600`
   for permissions. Missing files alone mean no extra trust; a stale copy from a failed earlier start is removed
   so it cannot enter the new task's saved state. The checked bytes are copied into the task's private
   runtime state as `task-ca-certificates.pem`; `ContainerSpec.volumeReadOnly` mounts this separate inode at
   `/porelo/host-ca.pem` with `:ro,Z`. The account's source keeps its label. The copy is mode 0644 inside private
   host state so the container's subordinate agent uid can read it. `NODE_EXTRA_CA_CERTS` names that mount, with
   its name and value passed through the same container specification.
3. After start, before any agent runs, `TaskCertificates.install` issues one `podman exec --user root` step against
   the recorded container id. If `update-ca-certificates` exists, it copies the mount to
   `/usr/local/share/ca-certificates/porelo-host.crt` and runs that command (Debian, Ubuntu, Alpine); otherwise, if
   `update-ca-trust` exists, it uses `/etc/pki/ca-trust/source/anchors/porelo-host.pem` and that command
   (Fedora/RHEL). No update command, or a failed update, refuses the start with one sentence and stops the
   container. This follows AGENTS.md's general **Fail closed and loud** rule; silently keeping only Node trust
   would claim incomplete system trust. Without the task file there is no mount, variable, or update step.
4. `TaskState` saves the snapshot and restores it readable by the agent after a reboot. `TaskControl` reapplies
   it after both resume paths. A task keeps the authority and environment it was created with; adding, replacing
   or removing that trust requires a new task, as container mounts and environment are fixed at creation.
5. `doc/credentials.md` describes both files and the stores; `doc/reach.md` records the added authority the task
   holds. The changelog entry is `added`.

**Decided:**
- A task trusts a separate file kept for tasks, never the host clients' `ca-certificates.pem`, so trusting the host's forge does not make a task trust an inspecting proxy.
- The authority is added by a step at each start, never as a layer of the image.
- A JVM not wired to the system store is out of scope.
- Measured on the VM under `--cap-drop ALL` as the container's root: `update-ca-certificates` succeeds on
  `ubuntu:24.04`, `debian:trixie` and `alpine:3.22`, and `update-ca-trust` succeeds on `fedora:44`. Curl refuses the
  authority with exit 60 before the update and returns HTTP 200 afterwards. These measurements need no repeat.

## Acceptance

- Red-first unit guards: `ContainerSpecTest.mountsAFileReadOnlyAndRelabelsItForThisContainerAlone`; a recording-runner
  start with the task file checks the independent copy, read-only private mount, Node environment and ordered root
  update against the recorded id. Malformed and group/other-accessible files refuse before create/start. A host
  clients' file alone supplies none of the task wiring. Missing or failing image tooling stops the container.
- Reboot restoration and a helper-free resume each have a unit guard first seen failing. The no-file guard is
  also proved by deliberately reading the host clients' file instead.
- The written acceptance scenario uses `--agent stub` with `ubuntu:24.04`. The suite starts its own loopback TLS
  service inside the task, using a fixture leaf signed by the host-side fixture authority. Curl uses no `--cacert`:
  exit 60 without the task file, HTTP 200 with it. This needs no host-services support or conditional skip; it
  carries the suite's usual `@slow` task tag.
- Two tasks need independent inodes/private labels, and the account's original file keeps its label.

## To be checked

- Run the written acceptance scenario on a VM; no VM run is part of this implementation handover.
- On a VM, check two concurrent tasks and the original file's SELinux label, plus git and Node against a TLS
  service signed by the authority. Curl's system-store behavior above is already measured; JVM behavior stays out
  of scope.
