# B234 — The Host's Authority In A Task's Trust Store

**Status:** Backlog.
**Size:** M - `runtime` (`ContainerSpec`), `app-base` (`TaskRunner`) and `core/net`, docs and an acceptance step; L if the JVM needs a design of its own.

**What must be true.** A task trusts a certificate authority the host names, so it reaches a service on the host
served under that authority's certificate without a project putting the certificate into its image.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) serves its
registry over TLS with a certificate authority made on the host. `~/.config/porelo/ca-certificates.pem` is read only
by Porelo's own clients on the host (the broker, a token purchase, `vault authorize`); nothing puts it into a task's
trust store, and the image's snippet is per project, not per host.

## Plan

Read in `TrustedCertificates`, `TaskLaunch`, `VaultAuthorizeCommand`, `VaultServeCommand`, `VaultProxy`,
`ContainerSpec`, `Podman` and `Containerfile`.

1. `~/.config/porelo/ca-certificates.pem` is read by the host's clients only. `ContainerSpec.volume` always mounts
   `:Z`, a private label, so the operator's own file mounted directly would be relabeled for one container.
2. **At a start, when the file is there:** it is checked as `TrustedCertificates.context` checks it, copied into the
   task's state directory and mounted read-only at a fixed path under the hand-in root (a read-only `:ro,Z`
   variant of `ContainerSpec.volume`); after the start one root step adds it to the image's store
   (`update-ca-certificates` or `update-ca-trust`), and `NODE_EXTRA_CA_CERTS` names it for Node.
3. **Docs:** where `ca-certificates.pem` is described, that a task trusts it too.
4. **Red first:** `ContainerSpecTest.mountsAFileReadOnlyAndRelabelsItForThisContainerAlone`.

## Acceptance

- With an authority in that file, `git ls-remote` and `curl` to a host service under it succeed in a task on
  `ubuntu:24.04` and on a Fedora base. Seen to fail first: "unable to get local issuer certificate".
- A second task alongside still reads its copy, the operator's file keeps its label, and without the file nothing is
  mounted or run.

## Open questions

- **The same file holds the host's corporate authorities**, a TLS-inspecting one included: does a task trust all of
  it, or a separate file kept for tasks? Recommended: a separate file, so trusting the host's forge does not make
  every task trust an inspecting proxy.
- **A step at each start, or a layer of the image** (which then depends on a host file, and a change rebuilds every
  project). Recommended: the start.
- **A JVM not wired to the system store** (an SDK manager's, a vendor image's): in scope? Recommended: not here.

## To be checked

- First, on the VM: where Ubuntu 24.04, Debian trixie, Fedora and Alpine read added authorities, whether the
  update commands run as the container's root under `--cap-drop ALL`, and whether git, curl, Node and the JVM's
  default trust see the result.
