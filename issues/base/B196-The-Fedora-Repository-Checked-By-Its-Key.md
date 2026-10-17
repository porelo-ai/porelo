# B196 — The Fedora Repository Checked By Its Key

**Status:** Estimated.
**Size:** S - one branch of `porelo-setup.sh`, the Fedora blocks of `README.md` and `doc/getting-started.md`, one
documents test; no contract another repository reads.

**What must be true.** On Fedora, as on Debian and Ubuntu, a machine takes Porelo's packages only from a repository
whose signature checks against Porelo's package key. Today `porelo-setup.sh` writes `/etc/yum.repos.d/porelo.repo` with
`gpgcheck=0` and no `repo_gpgcheck`, so dnf installs whatever that address serves. Yet the RPM repository's metadata
is signed by `porelo-packages` (`repodata/repomd.xml.asc`), and on Debian and Ubuntu the key is checked against the
fingerprint the script pins before apt trusts the repository. The Fedora repository gets the same treatment: the key
fetched, checked against that pinned fingerprint, imported, and the repository written to check against it. Found on
2026-10-17, while Fedora's VM moved to Porelo.

## What is measured

Measured on 2026-10-17, in a Fedora 43 container with dnf5 5.2.18:

- **Both channels' metadata verifies against the pinned key**: `repodata/repomd.xml.asc` of `porelo-dist-rpm/releases`
  and `/snapshots` is a good signature by `BF27A2FA637F505CAFBE431768D19D175D904415`.
- **With `repo_gpgcheck=1` and `gpgkey=` the key**, `dnf -y makecache` imports it and loads the repository. Without
  `-y` it asks before importing, so a run with no terminal must pass `-y`.
- **A wrong key is not a failure by itself.** dnf prints `repomd.xml GPG signature verification error`, skips the
  repository and exits 0, and `makecache` says "Metadata cache created". Only with `skip_if_unavailable=0` in the
  repository does it refuse, with exit 1.
- **The RPMs themselves stay unchecked:** they are not signed yet, so `gpgcheck=1` would refuse them; that is
  [B99](README.md#b99)'s, which signs them.

## Plan

- **`dist/dist-setup/porelo-setup.sh`, the `dnf` branch (`:205`):**
  - The key is fetched and its fingerprint checked as the `apt` branch does (`:166-190`). That check moves into one
    function both branches call, so there is one place that refuses a changed key (exit 3).
  - The checked key, armored, is written to `/etc/pki/rpm-gpg/RPM-GPG-KEY-porelo`, as Fedora keeps its own keys.
  - `REPO_BODY` gains `repo_gpgcheck=1`, `gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-porelo` and
    `skip_if_unavailable=0`; `gpgcheck=0` stays until [B99](README.md#b99).
  - `run dnf makecache -q` becomes `-y -q`, so the key is imported without asking.
  - `--show` prints the same steps as the `apt` branch does.
- **`README.md` (`:108`) and `doc/getting-started.md` (`:69`, `:438`):** the Fedora blocks fetch the key, check it
  against the fingerprint they already print, and write the same repository lines. The paragraph on `gpgcheck=0`
  (`:79`) says that the metadata is checked and the packages are not, until they are signed.
- **Not here:** `AgentLeg` in `porelo-buildtools` writes its own repository on every rented Fedora machine; it moves
  with the joint step that [B99](README.md#b99) plans.

## How it is tested

- **Red first:** a test tagged `documents` in `apps/app` beside `PackageKeyPinnedTest`. Every `[porelo...]` repository
  that the setup script, `README.md` and `doc/getting-started.md` write must say `repo_gpgcheck=1`,
  `skip_if_unavailable=0` and `gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-porelo`. It is red today, at every block.
- **The script on Fedora**, run as root in a Fedora 43 container. With the key as served, it writes the repository
  and the install works. With the pinned fingerprint changed, it stops with exit 3 and writes neither. With the
  served key replaced in the file after the script, `dnf makecache` fails.
- `PackageKeyPinnedTest` stays green: the documents still name only the one fingerprint.

## Acceptance

- On Fedora the setup script installs Porelo only from a repository whose metadata dnf checks against the pinned
  key, and a changed key stops the setup or the next `dnf` call with an error, not a skip.
- The documentation's Fedora blocks write the same repository as the script.
