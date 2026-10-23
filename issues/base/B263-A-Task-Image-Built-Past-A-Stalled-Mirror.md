# B263 — A Task Image Built Past A Stalled Mirror

**Status:** Idea.

**What must be true.** A task image whose Ubuntu archive fetches from the default mirror is still built when that one
mirror stalls: the build tries a second mirror after a bounded first attempt, and says which mirror stalled and in
which step. A project that names its own `image.package_sources` keeps them as it names them.

## Why

`Project.DEFAULT_PACKAGE_SOURCES` is one mirror, `http://azure.archive.ubuntu.com/ubuntu/`, and the image build puts
only that into every Ubuntu archive's `URIs:`. It is one mirror on purpose. Measured on 2026-09-11 from a rented
machine, apt spreads its requests over every URI it is given, so a slow second mirror beside a fast one dragged a
whole build to 4 minutes, against 8 s with Azure alone. A second URI is no fallback for a mirror that trickles.

But one node of that mirror sometimes trickles at about 60 kB/s, and apt's timeout fires only when no byte arrives,
so it never gives up on it. porelo's package check hung on it three times on 2026-10-23 on GitHub's runners, and is
now fixed by two bounded attempts on two mirrors. On the same day porelo-host's Molecule on GitHub's runner ended
with `podman build` of the scenario's task image "Timed out after 600 s", where the same scenario was green an hour
earlier: the same picture, inferred from the job's log, since apt's lines inside the image build are not shown. A
person's task start on a rented host would wait the same way.

So the default needs the package check's pattern at image build, not a second URI: a first attempt on the default
mirror bounded in time, then one on a second mirror, each alone.

## To be decided in the analysis

- Where the attempt is bounded: the whole `podman build`, or only its `apt-get` steps, so a slow image pull or a
  project's own `snippet` is not taken for a stalled mirror.
- Which second mirror, and whether a project may name the order.
- What the build shows of apt's own lines, so a stall can be told from a slow snippet.
