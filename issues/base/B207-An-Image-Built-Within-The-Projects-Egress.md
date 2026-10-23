# B207 — An Image Built Within The Project's Egress

**Status:** Backlog.
**Size:** L - the image build moves into a network Porelo controls, the project's egress applied to it through the
firewall or a proxy, with a package source the build may reach without being named; a design to decide first.

**What must be true.** An image's build reaches only what the project's egress allows, plus the package sources the
project trusts. A snippet that fetches from anywhere else fails at the build, with the address it tried.

## Why

A task runs behind its project's egress, but its image is built by `podman build` with the host's network, as root in
the build. A project's snippet and a repository's `porelo.yml` ([B192](README.md#b192)) can therefore fetch and
run anything while the image is made. For now that stays:
`porelo.yml` is read only from the reviewed default branch, the review ranks it dangerous, and `CODEOWNERS` gives it
to the operator. This issue closes the gap itself.

## The shape, to be analysed

- **Where the build's traffic goes.** Either the build gets the network of a task, so the same firewall rules apply,
  or it goes through an HTTP proxy that knows the project's egress. A snippet's `apt`, `pip` or `curl` honours a proxy;
  a tool that opens its own sockets does not, and then only the firewall holds.
- **Package sources.** The project's `package_sources` are reachable without being named in its egress, since the
  base image's own package manager needs them.
- **What a refusal says.** The build fails with the host it tried and the egress line that would allow it, as a task's
  refusal does today.
- **Cost.** The layer cache keeps working; a cached layer needs no network.

## Acceptance

- [ ] A snippet that fetches from a host outside the project's egress fails the build and names the host. The test is
  watched to fail against today's build first.
- [ ] A snippet that installs a package from a trusted package source, and fetches from an allowed host, builds.
- [ ] A repository's `porelo.yml` snippet is held to the same egress.
