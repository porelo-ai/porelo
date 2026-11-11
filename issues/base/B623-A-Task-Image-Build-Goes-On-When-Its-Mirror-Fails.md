# B623 — A Task Image Build Goes On When Its Mirror Fails

```json
{
  "$schema": "issue-1.json",
  "id": "B623",
  "title": "A Task Image Build Goes On When Its Mirror Fails",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "A task image's apt steps try the next mirror only when the first fails outright, never side by side, and keep downloaded packages in a build cache across a host's image builds."
}
```

**Why this size.** the Containerfile's two apt steps and the sources step, one cache mount, `ContainerfileTest` cases
over the generated text and one red build in the runtime's image test.

Filed 2026-11-11 from Machines' finding on a rented host (HO170's run, collected for the joint fix round), as this
repository's part of `porelo-project` ST317, "Builds take outside downloads from a cache". [B618](README.md#b618) covers the build's own
caches on GitHub; this is the task image a host builds for a person.

**What must be true.** On a host, a task image build ran `apt-get update` against `azure.archive.ubuntu.com` alone and
failed when that mirror failed, and the task did not start. The default is one mirror on purpose (`Project` and
`Containerfile` record why: apt spreads requests over every URI it is given, so a crawling second mirror slowed every
update to minutes). But a mirror that fails outright now ends the build, with no second try anywhere.

## Plan

- The sources step keeps one URI. The two apt steps run `apt-get update`; where it fails, they switch the sources to
  the next mirror of a fixed list (`archive.ubuntu.com` after azure; `deb.debian.org` for Debian; a project's own
  `build.package_sources` first, then the defaults) and update again, one mirror at a time, with the existing timeouts.
  Each switch is said in the build's output.
- Downloaded packages stay in a build cache shared by a host's image builds (`RUN --mount=type=cache` on apt's
  archives, which podman supports), so a rebuild fetches only what changed. Lists are not cached: an image installs
  against today's archive.
- Red first: a `ContainerfileTest` case expects the fallback and the cache mount in the generated text; a runtime test
  builds an image whose first mirror is an address that refuses at once and expects the build to pass on the second.
- Changelog entry under *Fixed*.

## As built

- **apt answers a refusing mirror with success.** The runtime test, run red against the old recipe, showed it:
  `apt-get update` printed "W: Some index files failed to download" and exited 0, and the install then ended with
  "Package 'git' has no installation candidate". So an update counts as failed on a non-zero exit or on that line;
  the exit code alone would never have switched.
- **The sources step gives each stanza one URI**: the first of the project's sources that serves its archive (applied
  last to first). Before, a project naming several put them side by side on one line.
- **Both apt steps go on, one mirror at a time**: the project's sources, then azure's, Canonical's, the temporary outage
  mirror `mirrors.edge.kernel.org` (`Containerfile.OUTAGE_MIRROR`, removed by [B624](README.md#b624)) and Debian's own
  (`Containerfile.FALLBACK_PACKAGE_SOURCES`), each once. A mirror is tried only where it changes a stanza (it serves
  that archive and is not the one already there), so a Debian base under the Ubuntu defaults is not asked twice.
  Each switch prints "porelo: apt-get update failed; trying the next mirror, <uri>". A warning moves on as a failure
  does.
- **The step fails only when every mirror failed outright** (a non-zero exit from each), with "porelo: apt-get update
  failed on every mirror". When every mirror only warned, or some warned and the rest failed, the step points the
  sources back at the first mirror that warned, updates again for its lists, says "every mirror warned; installing
  with the lists of the first that answered", and installs, as the recipe did before the fallback. That mirror's lists
  are the most complete, and the install's own error is the honest answer where they are not enough. The first
  warned mirror's sources are kept in a temporary folder under `/var/tmp` during the step and removed with it.
- **Downloaded packages stay in a build cache**: both apt steps mount `/var/cache/apt/archives` as
  `type=cache,id=porelo-apt-<base image>,sharing=locked`, one cache per base image so two distributions never share a
  file name; the sources step keeps downloaded packages (`Keep-Downloaded-Packages`) and removes the base image's
  `docker-clean`. The lists and apt's `*.bin` caches are removed as before, so an image installs against today's
  archive.
- A project whose source is a cache without an archive path has no mirror after it: every stanza then names the cache,
  and no archive's mirror matches.
- Tests: `ContainerfileTest` runs the generated step with a stand-in `apt-get` that answers a failing mirror as apt
  does (seven new cases, seen red first: six failed, the one for a mirror that changes nothing passed already), and
  one that refuses as well (exit 100). An eighth case, every mirror only warning, failed first (the step ended) and
  passes now: each mirror once, the first again, then the install.
  `TaskImageMirrorBuildTest` builds a real image with podman on `debian:trixie-slim` whose first mirror is
  `127.0.0.1:9`, and expects the build to pass on `deb.debian.org`. It needs podman and the network, so it runs only
  with `-Dporelo.test.podman.build=true`; run that way it failed on the old recipe and passed on the new one (2026-11-11).
  It runs on Debian, not Ubuntu, because Canonical's archive was down that day.

Not seen: an Ubuntu base on a host whose first mirror is unreachable, and the task starting afterwards (the
acceptance below). Canonical's archive was down on 2026-11-11.

## Acceptance

- The tests above, seen red first.
- On a host whose first mirror is unreachable, a task image builds and the task starts.
