# B623 — A Task Image Build Goes On When Its Mirror Fails

```json
{
  "$schema": "issue-1.json",
  "id": "B623",
  "title": "A Task Image Build Goes On When Its Mirror Fails",
  "state": "Implementing",
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

## Acceptance

- The tests above, seen red first.
- On a host whose first mirror is unreachable, a task image builds and the task starts.
