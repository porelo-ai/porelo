# B98 — The Image A Signature Names

**Status:** Estimated.
**Size:** M - the core model, the image build and start, `project`, `follow` and `doctor`, and the default project.

**What must be true.** A signed project's image is the image it names: an operator is told when a
project names its image by tag only, the digest is recorded when the image is built, and a start
says when it changed. A followed project that names its image by tag only does not start until a
person has allowed it once.

## Why

From a code review in `sokar-project`, "Project images are not digest- or signature-pinned", rated important.

A project file's `base_image` is any image reference, and it reaches the container file's `FROM` as written. A tag
such as `ubuntu:24.04` names whatever the registry serves under it on the day a machine first builds the image:
the signed project file pins a name, not content, so two machines following the same signed project can build
from different roots, and a registry that changed the tag changes what runs without any signature changing.

Weaker than it sounds, and said so: podman pulls only what is missing, so the image drifts on a new machine or
after a prune; and the same build installs packages from the distribution's mirrors, so a pinned base alone is not
a reproducible root either.

### What is enough

**The digest is enough.** A reference with `@sha256:` names exactly one image, and the signed project
file pins it. podman's signature policy (`policy.json`) is optional per project: a project that wants
the registry's signature checked as well can ask for it, and Sokar does not require it of any.

### What holds today

A digest is accepted: `base_image: "ubuntu:24.04@sha256:…"` builds from exactly that image. A project that wants
its signature to mean its image can pin one now.

## Plan

Read in the code on 2026-10-11: `base_image` is read by `ProjectReader` into `Project.baseImage()`
(`core/.../project/`), written into `FROM` by `Containerfile` (`runtime/`), and is part of the recipe
fingerprint the image carries as the label `org.fuin.sokar.recipe`. `TaskLaunch` prints `base image` at a
start; `DefaultProject` writes `ubuntu:24.04` (`BASE_IMAGE`); `Podman.imageRecipe` reads a label back with
`podman image inspect`. Nothing reads or keeps the base image's digest.

1. **`Project.baseImagePinned()`** in `core`: true when the reference carries `@sha256:`. Unit test first.
2. **`sokar project` and `sokar doctor`**: a line per project whose base image is a tag only, with the digest
   podman holds for it today (`Podman.imageDigest(image)`, `{{.Digest}}`) or "not pulled here". Red first: a
   `DoctorCommandTest` and a project listing test with a tag-only `base_image` that find no such line.
3. **The digest recorded at a build**: `Podman.buildArguments` adds `--label org.fuin.sokar.base-digest=<digest>`,
   read from the base image just before the build; not in the recipe fingerprint, so an image is not stale for
   it alone. A start that builds compares it with the label on the image it replaces and says
   `base image changed  <old> -> <new>`. Red first: a test with a fake runner whose base digest differs between
   two builds, and a start that prints nothing about it.
4. **A followed project with a tag-only image is refused until a person allows it once**: its start stops
   (`TaskLaunch`, where the `source` line says "followed, verified") with "its image is named by tag, which the
   signature does not cover", and names how to allow it. A person allows it with an option of `sokar project
   follow`, beside `--accept-rewrite`; the allowance and the digest the tag resolves to at that moment are kept in
   the project's record in `FollowedProjects`. From then on the start goes ahead, the digest is kept, and a start
   that finds the tag on another digest says `base image changed  <old> -> <new>` and keeps the new one. Red
   first: the launch test of a followed project with a tag-only image that starts without the allowance.
5. **The signature policy, per project**: an optional project file setting naming a podman signature policy; when
   it is set, the pull and the build of the base image pass `--signature-policy` with it, and a refused signature
   stops the build. Unset, the digest alone holds. Red first: a test with a fake runner that finds no
   `--signature-policy` for a project that sets one.
6. **The default project with a digest**: `DefaultProject` resolves `ubuntu:24.04` to its digest when it writes
   the file (pulling it when podman has none) and writes `ubuntu:24.04@sha256:...`; offline, it writes the tag and
   says so. Red first: a test of the written file with a fake runner.
7. `doc/project-file.md` says what a digest buys and what it does not (the packages the build installs), and the
   optional signature policy.

## Acceptance

- **`sokar project` and `sokar doctor` say when a project's image is a tag only**, and what its digest is today.
- **The resolved digest is recorded with the task image**, and a start says when it changed since the last build.
- **A followed project's image carries a digest, or its start is refused until a person allows the tag once**;
  after that the digest is kept and a change of it is said at the start.
- **A project may name a podman signature policy**, and then its image is checked against it; without one the
  digest alone holds.
- **The default project writes a digest** when it is created.
- **Seen to fail:** a test with a tag-only `base_image` goes red when `sokar project` or `sokar doctor` does not
  say so; a test that rebuilds after the tag moved to another digest goes red when the start does not say it
  changed; a test of a followed project with a tag-only image goes red when it starts without the allowance, or
  when, once allowed, a moved tag is not said; a test of a project naming a signature policy goes red when the
  build does not pass it; a test of a freshly created default project goes red when its `base_image` has no digest.

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F9,
answering NCSC/CISA secure development, OWASP `LLM03`, Sysdig 6, AISVS `C06`.
