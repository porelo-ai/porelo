# B440 — An OpenSSH Below The Floor Is Refused In Words

**Status:** Backlog.
**Size:** S - one probe, one refusal, one page.

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
