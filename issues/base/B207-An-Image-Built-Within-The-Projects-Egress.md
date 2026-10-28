# B207 — An Image Built Within The Project's Egress

**Status:** Backlog; its plan written, two questions for the operator open.
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

## Plan

Read in porelo at 2ef7cde6: `Podman.buildArguments` (`runtime`, `:458`) runs `podman build` with no `--network`, so
every `RUN` has the account's network; `TaskRunner.start` (`app-base`, `:307`) writes a task's ruleset
(`NftRuleset`, from the security class and the declared egress) and its resolver sidecar, and the container's
annotations have the hooks load both into its network namespace before it runs (`doc/security.md`, "The firewall").

**Measured on this desktop (rootless podman 5.7.0, 2026-10-28):** `podman build --network ns:/proc/<pid>/ns/net`
with the namespace of a running rootless container runs every `RUN` in that namespace (with the container's network
off, the build resolved nothing; as today it did), and nft rules loaded into it from outside, with `podman unshare
nsenter --target <pid> --net nft -f -`, hold for the build: an allowed address answered, another did not, the same
build as today reached both.

1. **The build in a task's network (recommended).** Before `podman build`, Porelo starts a build guard for the
   project: a container from the project's base image that only sleeps, started through the same path as a task,
   so its hooks load the project's ruleset and its resolver. The allowed names are the project's egress plus its
   `package_sources` (or the base image's default archive when it names none), so the base image's package manager
   works without the project naming its mirror. `podman build` then gets `--network ns:/proc/<guard pid>/ns/net
   --dns 127.0.0.1`; the guard is removed when the build ends, fails or is interrupted.
2. **What a refusal says.** A build that fails while the guard logged drops names the hosts it tried, from the
   resolver's refused names and the firewall's NFLOG prefix, and the egress line that would allow each, as a task's
   refusal does.
3. **Not a proxy.** A proxy that knows the egress holds only for a tool that honours it; a tool that opens its own
   sockets passes, so the firewall would be needed anyway. One mechanism, the task's, is checked once.
4. **What stays:** the layer cache (a cached layer runs no `RUN`); the build's limits; `--no-cache` and the agent's
   epoch. A repository's `porelo.yml` snippet ([B192](README.md#b192)) is built the same way.
5. **Red first:** an acceptance scenario whose snippet fetches from a host outside the egress fails its build naming
   the host, seen to pass against today's build first; one that installs a package from the default archive and
   fetches from an allowed host builds. `PodmanTest` asserts the build's `--network ns:` and `--dns`; a unit test
   that the guard is removed when the build throws.
6. **Docs:** `doc/security.md` (the build behind the same firewall), `doc/project-file.md` (`package_sources` reach
   the build without being named), the changelog.

## Acceptance

- [ ] A snippet that fetches from a host outside the project's egress fails the build and names the host. The test is
  watched to fail against today's build first.
- [ ] A snippet that installs a package from a trusted package source, and fetches from an allowed host, builds.
- [ ] A repository's `porelo.yml` snippet is held to the same egress.

## Open questions

- **For the morning (operator):** the build in a task's network (step 1), or a proxy? Recommended: the task's
  network, measured to hold; a proxy alone lets a tool with its own sockets through.
- **For the morning (operator):** a snippet that fetches from a host its project's egress does not name stops
  building once this lands. That is the point of it, and it breaks such projects until their egress names the host.
  Ship it as is, or with a release note telling projects to check their egress first?

## To be checked

- That the guard's resolver serves the build with `--dns 127.0.0.1`, and that the hooks load into a guard as into a
  task, on Ubuntu 26.04 and on Fedora 44 with SELinux enforcing.
- The base image's default archive per supported base image, for a project naming no `package_sources`.
