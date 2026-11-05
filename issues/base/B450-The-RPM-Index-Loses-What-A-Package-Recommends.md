# B450 — The RPM Index Loses What A Package Recommends

**Status:** Backlog.
**Size:** unknown until its first question is read; M if the build must write the index.

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

## Acceptance

Moved here from Agent Ecosystems' issue, as they prove nothing while the index names no recommendation, on clean
containers of Fedora:

- A default `dnf install porelo` brings the plugin and the filter.
- `--setopt=install_weak_deps=False` leaves them out.
- After the plugin is removed, an upgrade to a newer Porelo does not bring it back.
- `dnf repoquery --recommends porelo` names both, which it does not today.
