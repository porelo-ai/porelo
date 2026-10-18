# B49 — What The Build Trusts To Run Beside Its Secrets

**Status:** Implementing.
**Size:** S - what is left here is one workflow step and two checks by a person; the other repositories' share is
theirs.

**What must be true.** Nothing runs beside this product's credentials, in any of its repositories' builds, that was
fetched by a name its owner may repoint.

## Why

The same finding came back as the one high-severity item from reviews of the agent repositories, and the operator
asked for it to be written here. It is not one repository's problem: every repository in this product has the
shape described below.

### What it was before the pinning

Every workflow step that is not ours is fetched by a **mutable tag**:

    actions/checkout@v7          graalvm/setup-graalvm@v1
    actions/upload-artifact@v7   actions/download-artifact@v8
    jfrog/setup-jfrog-cli@v5     crazy-max/ghaction-import-gpg@v7

A tag is a name its owner may point anywhere. Fetching `@v7` is a promise to run whatever that name
refers to at the moment the job starts - a new release, a retargeted tag, or the work of whoever
took the account over since the last build.

**What is standing next to those steps is the whole of this product's credentials.** In porelo
alone: `HETZNER_API` and `HETZNER_SSH`, which rent machines and log into them;
`JF_ACCESS_TOKEN`, which publishes packages people install; `OSS_SONATYPE_TOKEN` and
`OSS_SONATYPE_USERNAME`, which publish to Central; and `OSS_SONATYPE_GPG_PRIVATE_KEY` with its
passphrase - **a signing key, handed deliberately to a third-party action**. The agent
repositories add a token that can open and merge a pull request in the update workflow.

Nothing about this is hypothetical machinery: it is the ordinary way these workflows are written,
and it is why this finding is the same in every repository.

## The shape

1. **Every third-party action is pinned to a commit, not a tag.** A commit names one tree; a tag
   names whatever it is pointed at. Our own reusable workflows and actions, if any, are the only
   exception, and only because their history is ours.
2. **The tag stays visible.** A bare forty-character hash is unreadable and unreviewable, so each
   pin carries the human version beside it as a comment. A pin nobody can read is a pin nobody
   updates.
3. **Something keeps them current, and it is named.** This is the half that decides whether the
   requirement helps: **a pin without an update process rots, silently, and trades a live risk for
   a stale one.** A build that runs a two-year-old action with a known flaw is not safer than one
   that ran the current tag. Whatever does it - Dependabot, a scheduled job, a person with a
   calendar - has to be written down here and be something that produces a review rather than an
   automatic merge.
4. **It holds in every repository**, because the credentials are distributed across them and the
   weakest workflow is the one that decides. The agent repositories, the interface and this one are
   one surface.
5. **A new workflow cannot quietly reintroduce a tag.** Whatever enforces this is part of the
   build rather than a habit: a check that fails on an unpinned third-party `uses:`.

### Decided

- **Dependabot keeps the pins current, in every repository.** `.github/dependabot.yml`, the
  `github-actions` ecosystem, weekly, one grouped pull request, a three-day cooldown (the same release age
  as every other pin), never merged automatically.
- **The signing key goes to no third-party action.** `ghaction-import-gpg` is replaced by our own step:
  `gpg --batch --import` from the secret. The passphrase is not needed there; Maven's signing passes it
  with loopback pinentry from `settings.xml`.
- **GitHub's own actions are held to the same rule.** `actions/checkout`, the artifact pair and
  `actions/cache` are pinned like any other; the check has no exception for them.
- **The runner is named by its version, the image within it is out of scope.** Every job runs on `ubuntu-24.04`, as
  the builds of the packages do; the image behind that name still changes weekly, which is recorded so it is not
  mistaken for an oversight.

### How it is built here

- Every `uses:` names a commit with its version beside it: `actions/checkout@3d3c42e… # v7.0.1`,
  `actions/upload-artifact@043fb46… # v7.0.1`, `actions/download-artifact@3e5f45b… # v8.0.1`,
  `actions/cache@55cc834… # v6.1.0`. Each commit was read from
  the release tag it stands for.
- **The check is `porelo-release check-actions [DIRECTORY]`**, default `.github`. It refuses a tag, a
  branch, a commit without its version, and a container image without its digest, and says how to pin
  each. Only a step in the repository itself (`./...`) is exempt. Here a unit test runs it over this
  repository's `.github`, so a new step that names a tag fails `Build and unit tests`. An agent
  repository runs the same command from its own workflow.
- **What else is fetched by a name**, in jobs that hold a credential:
  - Maven itself, which `mvnw` downloads in every job, `Publish` included: now pinned by
    `distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties`. The SHA-256 was taken from a
    download whose SHA-512 matched the one Maven Central publishes.
  - GraalVM (`pinned-jdk`, and on the snapshots): already checked against its pinned SHA-256.
  - The JFrog CLI is gone, decided 2026-10-18. `setup-jfrog-cli` downloaded it into `Publish` without a checksum;
    `Publish` and `artifactory-smoke.yml` now speak to Artifactory's REST interface with `curl`, through
    `.github/scripts/artifactory.sh`. The token goes into a curl config file of mode 600, never onto a command line,
    and each upload carries its SHA-1 and SHA-256, which Artifactory checks against the bytes.
  - `Publish` reads Artifactory's package indexes with `curl`, and runs nothing it reads.

## Plan

Built and on main, as "How it is built here" says: every `uses:` in `.github/workflows` and
`.github/actions/pinned-jdk/action.yml` names a commit with its version; `.github/dependabot.yml`; `check-actions`
bound to the root POM's `validate` (`pom.xml`, execution `check-actions`, `porelo-release` from porelo-buildtools,
`release/src/main/java/org/fuin/porelo/release/CheckActions.java`); the Maven wrapper by `distributionSha256Sum`; the
signing key imported by our own step.

Left here, in order:

1. **The first `Publish` without the JFrog CLI**, on the next push of code: its packages uploaded, indexed and
   installable on both legs as before. Before that push, `artifactory-smoke.yml` started by hand on a branch with
   the change: it makes the same calls from the same file, against the real repositories.
2. The hand check of `Publish` after this pinning: a person reads each step and the commit each pin names.

The agent repositories and the interface pin, add the same `dependabot.yml` and run `check-actions` themselves.

## Acceptance

- `grep` over every workflow in every repository finds no third-party `uses:` naming a tag or a
  branch. Seen to fail: `porelo-release check-actions` over a repository's `.github` refuses it. Here met; open in
  the agent repositories and the interface, each for itself: pin, add the same `dependabot.yml`, and run
  `check-actions` in their build. Theirs to do; the tool is published with `porelo-release`.
- Each pin carries the readable version beside it. Seen to fail: `check-actions` refuses a commit without its
  version.
- Adding a step that names a tag fails a build, and the failure says what to do about it. Seen to fail: the unit
  test running `check-actions` over this repository's `.github` fails `Build and unit tests`.
- The update process exists, has run at least once, and its result was reviewed by a person before
  it landed. Met: Dependabot's first grouped pull request, porelo #5, was merged by a person as `9e8c4c19`.
- **Dependabot's first grouped pull requests, prepared for that review, 2026-10-18:** porelo #5 and porelo-build-github
  #2, "Bump the actions group", made after phase 0's push. Each new pin was read back against its release tag through
  GitHub's API, and each is that tag's commit:
  - `jfrog/setup-jfrog-cli` v5.2.0 to v5.3.0 at `1e58450` (released 2026-10-14);
  - `actions/upload-artifact` v7.0.1 to v7.0.2 at `cf430e0` (2026-10-07);
  - `actions/download-artifact` v8.0.1 to v8.0.2 at `9000827` (2026-10-07), porelo only.

  Both pull requests' Builds are green, and so is `check-actions` on the new pins; a pull request's Build runs no
  rented leg. What is left is the person's: read the three releases' notes, then merge, which starts one Build on
  `main` with both legs.
- The most privileged workflow - the one holding the signing key - is checked by hand after the
  first pinning, because that is the one where being wrong costs the most. Open: the hand check of `Publish`,
  which holds the signing key, after this first pinning.
- **Nothing else is fetched by a name either.** `grep` for a `curl` or a `wget` in any job that
  also holds a publishing credential, and pin whatever it finds by digest. An action is not the
  only thing a workflow runs: a release binary downloaded into a publishing job has the same
  trust and none of the ceremony, and the worst case is the one that writes the packages
  themselves. Porelo is believed to fetch nothing this way and that has not been proved, which is
  exactly why it is a criterion rather than a note. Built: the JFrog CLI, the one such download, is dropped for
  `curl`; open until the first `Publish` without it is green. Checked on this machine against a stand-in server: the
  paths, the properties and both checksums arrive as Artifactory expects them; a token it refuses, a missing file
  and a missing token each fail the step; and `set -x` shows no token.
