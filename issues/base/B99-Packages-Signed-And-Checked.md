# B99 — Packages Signed And Checked

**Status:** Analyzed.
**Size:** L - crosses seven repositories on one day; Sokar's part is CI signing, one shared check, setup and docs.

**What must be true.** An operator installing Sokar or an agent gets only packages, and a setup script, whose
signature the machine has checked against the repository's packaging key.

## Why

From a code review in `sokar-project`: `sokar-buildtools`' "Fedora package installation disables signature checking", and the
agent repositories' "Release RPMs are built with signing disabled", shared with Sokar. Placed Soon, with the agents'
side in their repositories.

Sokar's RPMs are built with signing off (`dist/dist-rpm/pom.xml`, `rpm.sign.skip`), so the Fedora repository is set up
with `gpgcheck=0` - in `sokar-setup.sh`, in the documentation (`doc/getting-started.md`, "deliberate, and
temporary"), and on every rented machine a leg installs on (`AgentLeg` in `sokar-buildtools`). Integrity rests on
TLS to the project's own repository; `repo_gpgcheck` is not set either.

## The shape

- **Snapshot and release RPMs of Sokar and of every agent are signed** with the repository's packaging key: the
  key the Debian repository's metadata is already signed with, so a machine trusts one fingerprint for everything
  it installs from Sokar. Its private half is held by the project's owner only, and reaches CI only as a
  repository secret.
- **Every place that sets up the repository checks them**: `gpgcheck=1`, `repo_gpgcheck=1` and the key named by
  `gpgkey=`, in `sokar-setup.sh`, the documentation and the legs, moved in one change.
- **Every repository that publishes to the same package repositories switches signing on with its next release**:
  `sokar`, the matrix transport and its homeserver, the message filter, the three agents, released together.
  Switched on for one alone, the setup refuses the others. So the key, the CI secret that carries it and how a
  repository signs with it are written here first, and every repository moves in that release.
- **The setup script is signed too.** `sokar-setup-<version>.sh` is the one Sokar interface that is a file, and a
  wizard runs it as root on a machine that has nothing of Sokar's yet (a code review of `sokar-frontend`).
  Each version gets a detached signature beside it, made with the same key, so a caller holding that public key can
  check exactly the bytes it is about to show and run; `latest` is only a pointer to a version.

## Plan

Read in the code on 2026-10-11:

- `dist/dist-rpm/pom.xml` sets `rpm.sign.skip` to `true` and says signing is "on for releases, where the key
  exists" - **but no workflow sets it to `false`**: `build.yml` builds the packages with `-Pdist verify` and no
  such property, so release RPMs are unsigned too. `sokar-omp` and `sokar-pi` carry the same property, as
  `true`, with the same plugin; other repositories build their RPM with the other plugin `sokar-parent` pins.
- `build.yml` imports a key for Central's jar signatures (`OSS_SONATYPE_GPG_PRIVATE_KEY`); the packaging key the
  Debian repository's metadata is signed with is a different one, its fingerprint in `doc/getting-started.md`.
- `gpgcheck=0` stands in `dist/dist-setup/sokar-setup.sh`, `doc/getting-started.md` (three places) and
  `AgentLeg` in `sokar-buildtools`.
- The setup script is uploaded twice, as `sokar-setup-<version>.sh` and as a copy named `sokar-setup-latest.sh`.

**The answers for the other repositories:**

- **One shared check, not a step per repository**: `sokar-release check-signed <packages...> --key <file>` in
  `sokar-buildtools`, which every repository already reaches through `sokar-parent`'s `ci-tools` profile (as
  this tree reaches `check-releases`). Each repository's publish job calls it on what it is about to upload,
  just before the upload, and a package that is unsigned or signed by another key stops the job.
- **The signature's form**: an RPM carries its OpenPGP signature in its header, as `rpmsign --addsign` writes it;
  the check runs `rpmkeys --checksig` against a keyring holding only the packaging key (on the Ubuntu runner,
  the `rpm` package). The setup script gets a detached, ASCII-armored OpenPGP signature, `sokar-setup-<version>.sh.asc`.
- **On a fresh machine** the script is checked with `gpgv` against the dearmored packaging key: present on a
  fresh Debian, Ubuntu and Fedora alike, and it does nothing but verify.
- **Which version**: `sokar-setup-latest` becomes a one-line file holding a version, never a script; a caller
  reads it, fetches `sokar-setup-<that version>.sh` and its `.asc`, verifies, then shows and runs it.
- **Which leg runs it**: `dist/package-check`'s clean Fedora install here (it installs the RPM it built, so it
  sees `rpm -K` on it before the upload), and `AgentLeg` in `sokar-buildtools` for every agent leg, with
  `gpgcheck=1` and `repo_gpgcheck=1` once every repository signs.

Steps in Sokar, in order:

1. **The key and the secret written down here** (Acceptance's first condition): the packaging key whose
   fingerprint `doc/getting-started.md` already names, the repository secret's name (the same in every
   repository), and the `%_gpg_name` macro the build passes. The private half is put into each repository's
   secrets by the project's owner; no agent and no workflow log ever holds it. Nothing is switched on before.
2. **`check-signed` in `sokar-release`** (`sokar-buildtools`), with a test on an unsigned and a foreign-signed RPM
   built in the test - red first, as the check that does not exist yet.
3. **This tree's `build.yml`**: import the packaging key, build with `-Drpm.sign.skip=false` on every channel,
   run `check-signed` before `jf rt upload`, sign the setup script, upload the `.asc` and the `latest` pointer.
4. **In the next release, together with the other repositories**: `sokar-setup.sh`, `doc/getting-started.md` and
   `AgentLeg` move to `gpgcheck=1`, `repo_gpgcheck=1`, `gpgkey=`; the `getting-started` paragraph on why it is
   off goes.

## Acceptance

- Every snapshot and release RPM of Sokar and of every agent, and every setup script, carries a signature by the
  packaging key - the one fingerprint the Debian repository's metadata is signed with. Seen to fail: `rpm -K` on
  a published package reports it unsigned or signed by another key, or `check-signed` given a keyring with any
  other fingerprint passes.
- `sokar-setup.sh`, the documentation and the legs set `gpgcheck=1`, `repo_gpgcheck=1` and `gpgkey=`. Seen to fail:
  installing a package with a broken or missing signature from the configured repository succeeds.
- The key, the CI secret that carries it and how a repository signs with it are written here before any repository
  switches signing on, and `sokar`, the matrix transport and its homeserver, the message filter and the three agents
  sign from their next release on, together. Seen to fail: a setup on a clean machine refuses a package from one of
  them.
- A test refuses an unsigned package before it is published. Seen to fail: the build publishes an RPM with signing
  skipped.
- Each `sokar-setup-<version>.sh` has a detached signature beside it, made with the same key. Seen to fail:
  verifying a published setup script against its signature with the public key fails, or a version has none.

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F9,
answering NCSC/CISA secure development, OWASP `LLM03`, Sysdig 6, AISVS `C06`.

## To be checked

- Whether the RPM repository's metadata is signed as the Debian one's is, which `repo_gpgcheck=1` needs
  (Agent Core's, read in the package repository's settings).
