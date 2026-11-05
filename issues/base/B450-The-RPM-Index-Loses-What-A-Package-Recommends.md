# B450 — The RPM Index Loses What A Package Recommends

**Status:** Backlog; waits on a reading of the RPM repository's settings in Artifactory, which an agent cannot make.
**Size:** M if the build must write the index; the settings-only alternative is not yet verified.

**What must be true.** A default `dnf install porelo` brings what the package recommends, as `apt` does.

The operator's word: the docs now, the fix after the test.

## Found

Agent Ecosystems measured on Fedora 44, against `snapshots`: the `.rpm` names the Maven plugin and the filter under
`Recommends`, but Artifactory's index holds no `rpm:recommends` in any of its 489 packages, and `dnf repoquery
--recommends` answers nothing. The same two `.rpm` files in a local source indexed by `createrepo_c` bring the plugin on a
default install. So the index loses it, not the package. The Coordinator read the same of the index.

## What Is Asked

1. Read whether Artifactory has a setting that keeps these fields.
2. Otherwise the build writes the RPM index with `createrepo_c` and uploads it, with Artifactory's own calculation off for
   that repository. That changes how every Porelo repository publishes.
3. Then `doc/getting-started.md` no longer names them in its `dnf` lines.

## Plan

- Verify whether the deployed Artifactory version can preserve RPM weak dependencies through a repository setting.
  No setting is selected until its resulting primary metadata contains the recommendations.
- If that works, enable it and recalculate the affected channels before changing the installation guide.
- Otherwise coordinate one index publisher for `porelo-dist-rpm`, with Artifactory's own calculation disabled.
  It must index all retained RPMs in each channel with `createrepo_c`, including packages uploaded by other
  repositories. Concurrent publishes and package retirement must not overwrite each other's index entries.
- Reproduce the missing recommendations against the published index first, then verify all four acceptance cases
  below against the corrected index. Only then remove the explicit recommended packages and the workaround note
  from `doc/getting-started.md`.

## Checkout findings and decisions

- `dist/dist-rpm/pom.xml` already declares `porelo-message-sluice-filter` and `porelo-ecosystem-maven` under
  `recommends`. Keep them weak dependencies; making them required would defeat the opt-out acceptance case.
- `.github/workflows/build.yml` uploads RPMs through `.github/scripts/artifactory.sh`. Its RPM index check only
  looks for `primary` in `repodata/repomd.xml`; it does not inspect recommendations. Neither file configures
  Artifactory's RPM metadata calculation or generates a local index.
- `dist/package-check` owns packaged installation checks. `ChannelLine` checks the published Debian channels;
  `Install.fedora()` installs a local RPM. Neither verifies weak dependencies from the published RPM index.
  The four cases need clean Fedora containers against the channel, with a second Porelo version for the upgrade.
- Keep the getting-started workaround until those cases pass. There is no shipping behavior change or changelog
  entry from this investigation.

## External work and verification still needed

- Read the deployed Artifactory version's supported RPM metadata settings and inspect the effective configuration
  of `porelo-dist-rpm`. This has not been verified: external documentation, the service, credentials, containers
  and VMs are outside the permitted investigation. The fallback cannot yet be selected over a settings-only fix.
- If the fallback is necessary, configure Artifactory to stop calculating this repository's index and coordinate
  every repository that uploads or retires RPMs there to invoke the same index publisher after its package change.
  The publisher must retain other repositories' packages, preserve channel paths and metadata signing, serialize
  concurrent changes, and publish referenced metadata before replacing `repomd.xml`. The exact publisher inventory
  must be obtained from the publishing repositories; it is not established by this checkout.
- Shared publishing tooling, if needed, belongs in `porelo-buildtools` and must build and publish before this
  repository pins and calls it. No changes to another repository or to the desktop window are made here.
- No scenario in `acceptance/` exercises installation from the published RPM index: those scenarios drive an
  already prepared account. This needs the clean Fedora package-installation checks above, rather than a task
  acceptance scenario. The existing machine scenario `doctor reports whether this machine can run a task` is a
  later smoke check only; it cannot establish any of the four acceptance cases.
- No behavioral regression test has run red or green, and the fix is not ready for Review.

## Acceptance

Moved here from Agent Ecosystems' issue, as they prove nothing while the index names no recommendation, on clean
containers of Fedora:

- A default `dnf install porelo` brings the plugin and the filter.
- `--setopt=install_weak_deps=False` leaves them out.
- After the plugin is removed, an upgrade to a newer Porelo does not bring it back.
- `dnf repoquery --recommends porelo` names both, which it does not today.
