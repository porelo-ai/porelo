# B440 — An OpenSSH Below The Floor Is Refused In Words

**Status:** Backlog.
**Size:** S - one probe, one refusal, one page.

**What must be true.** The documents state the lowest OpenSSH Porelo works with, and below it Porelo refuses in words that
name the version found and the version needed, rather than failing later in a `ssh-keygen` call.

## Why

Porelo signs and verifies with `ssh-keygen -Y`, whose operations arrived over several OpenSSH releases, and nothing says
which one it needs. A machine below that learns it from whatever call fails first.

The floor given is 8.7. **One question is open:** OpenSSH's release notes add `-Y match-principals`, which
`AllowedSigner` and `HostArrivals` call, in 8.9, and 8.9 also makes `-Y find-principals` check a plain key's validity in an
allowed signers file. So 8.7 may be too low. This was read in the notes and the calls; no older OpenSSH was run. Settle it
before building, with a run on 8.7 or 8.8 if 8.7 stays.

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

Every supported system holds 10.0 or newer, so the floor changes nothing for them.
