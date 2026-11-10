# B440 — An OpenSSH Below The Floor Is Refused In Words

```json
{
  "$schema": "issue-1.json",
  "id": "B440",
  "title": "An OpenSSH Below The Floor Is Refused In Words",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 97,
  "blockedBy": [],
  "covers": "An OpenSSH below the stated floor is refused in words, and the documents state the floor."
}
```

**Why this size.** one probe, one refusal, one page.

**What must be true.** The documents state the lowest OpenSSH Porelo works with, and below it Porelo refuses in words that
name the version found and the version needed, rather than failing later in a `ssh-keygen` call.

## Why

Porelo signs and verifies with `ssh-keygen -Y`, whose operations arrived over several OpenSSH releases, and nothing says
which one it needs. A machine below that learns it from whatever call fails first.

The floor is 8.9. Porelo calls `ssh-keygen -Y match-principals` (`AllowedSigner`, `HostArrivals`), and
[OpenSSH 8.9's release notes](https://www.openssh.org/txt/release-8.9) add that operation; 8.8's do not name it. 8.9 also
makes `-Y find-principals` check a plain key's validity in an allowed signers file. This rests on the release notes and the
calls; a run on an older OpenSSH is not needed.

## Versions

From Repology's table of distribution packages, read 2026-11-05:

| System | OpenSSH |
|---|---|
| Ubuntu 26.04 | 10.2p1 |
| Ubuntu 25.10 | 10.0p1 |
| Ubuntu 24.04 | 9.6p1 |
| Ubuntu 22.04 | 8.9p1 |
| Debian 13 | 10.0p1 |
| Debian 12 | 9.2p1 |
| Fedora 44 | 10.2p1 |
| Fedora 43 | 10.0p1 |
| Fedora 42 | 9.9p1 |
| AlmaLinux 9 | 9.9p1 |
| AlmaLinux 8 | 8.0p1 |

Every supported system holds 10.0 or newer, so the floor changes nothing for them. Ubuntu 22.04 stands exactly on it.

## As built

- **The floor and its reading are one class**, `OpenSshFloor` in `app-base`: it runs `ssh -V` through the context's runner and
  reads standard error, as the three answers `Enough`, `TooOld` and `Unread`. `ssh-keygen` has no option that prints its
  version, so `ssh` answers for it.
- **Only digits read off the banner reach a line.** What `ssh` wrote is otherwise never repeated.
- **`porelo doctor` has a line `openssh`**: the version when it is enough, `missing` below the floor (which makes doctor exit
  69), `unknown` when it cannot be read or `ssh` does not run, which leaves the machine ready and says so.
- **A start refuses in `TaskLaunch.launchSteps`, right after podman's floor and before the hooks**, exit 69, with nothing built
  or created. That place is already where a machine it cannot run on is refused, and both the command line and the daemon reach
  it. `TaskLaunch.startExisting`, which brings a stopped task back without passing there, hands such a machine on to the launch,
  as it does for hooks that cannot be repaired. A dry run returns before the refusal, as it does before podman's. A version that
  cannot be read prints nothing at a start.
- **`task attach` and the other verbs on a task that already runs are not refused**: the floor is held where a task starts.
- **The floor is stated in `doc/getting-started.md`**, which `OpenSshFloorTest` reads.
- **Not run on an OpenSSH below 8.9**, as decided above: the tests fake the answer of `ssh -V`.
