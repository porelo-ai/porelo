# B764 — A Refresh On A Host Without Porelo-Host Says So

```json
{
  "$schema": "issue-1.json",
  "id": "B764",
  "title": "A Refresh On A Host Without Porelo-Host Says So",
  "state": "Review",
  "size": "XS",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "`porelo org host refresh` runs `/usr/sbin/porelo-host` by its absolute path, and a host where it is missing (env's 127) or not executable (126) is refused with 69 in porelo's words, naming the program and the package to install",
  "openQuestions": 0
}
```

**Severity:** high - a person handing a new host its token reads only env's two lines and exit 70, and cannot tell
that the host lacks the package rather than that porelo is broken. It blocks setting up a host; nothing leaks.

**Why this size.** XS: one argv and one exit mapping in `OrgHostRefreshCommand`, and its test. Estimate: 9 minutes
(Core's median for XS).

**What must be true.** A refresh against a host that has no `porelo-host` says so: which program is missing, on which
host, and that the host's porelo-host package must be installed or updated first.

Seen on a fresh Ubuntu 26.04 host: `porelo org host refresh <host>` printed `env: 'porelo-host': No such file or
directory` and `env: use -[v]S to pass options in shebang lines`.

Measured in `ubuntu:26.04` (`env (uutils coreutils) 0.10.0`): uutils env finds a bare name in the `PATH` it is given,
as GNU env does, and prints exactly those two lines, exit 127, for any program it cannot find, an absolute path too.
So the argv is not at fault. The host has no `porelo-host` in `/usr/sbin:/usr/bin:/sbin:/bin`. Every other `env` argv
porelo builds was measured there too and works under uutils: `env -C / NAME=value <program>` through `runuser`, and
`/usr/bin/env` resolved by `SystemProgram` to `/usr/lib/cargo/bin/coreutils/env`, which still dispatches as env.

## Plan

- `OrgHostRefreshCommand.send`: the ssh argv names `/usr/sbin/porelo-host`, where the package installs it, instead of
  the bare name. This takes the first step of [B592](README.md#b592)'s plan.
- An exit of 127 or 126 is refused with 69 and the words "porelo-host is not installed at /usr/sbin/porelo-host on
  <host>; install or update the host's porelo-host package, then retry", whatever the host printed before it.
- Red first: `OrgHostRefreshCommandTest` - the argv ends in `/usr/sbin/porelo-host org refresh`, and 127 and 126 map
  to 69 with those words. Both red today: the argv names the bare name, and 127 maps to 70 with no words.

## Built

- `OrgHostRefreshCommand` runs `/usr/sbin/porelo-host org refresh` under `env -i`, and answers a host's 127 or 126
  with 69 and "porelo-host is not installed at /usr/sbin/porelo-host on <host>; converge the host with porelo-host
  collection 0.5.13 or later, which installs it, then retry". Agreed with Agent Machines; porelo-host's HO190
  releases 0.5.13 and moves the window's pin to it.
- The cause on the host that showed it: it was set up with collection 0.5.12, which installs only
  `/usr/local/sbin/porelo-host-person`. The collection that installs `/usr/sbin/porelo-host` (porelo-host's HO156)
  removes that file, so porelo gets no fallback to it. A link from `/usr/sbin/porelo-host` to it bridged the host
  until then.
- After the program is found, uutils env prints nothing of its own; the "-[v]S" line came only with the missing
  program.
- `doc/commands.md` and the unreleased changelog entry name the absolute path and the refusal.
- Red first: `OrgHostRefreshCommandTest.aHostWithoutPoreloHostIsNamedAsSuch` (127, 126) and the argv in
  `sendsTheTokenOnlyToPinnedSshStandardInput`, three failures, then 25 tests green, 0 skipped.

- The reading (agy, gemini-4-argon-high, 09:20:42-09:27:02Z), each finding checked in the code, fixed after the push
  in one commit on top:
  - Yellow, fixed: 126 means env found the file but could not run it, yet the words said "not installed". They say
    "not executable" for 126 now, red first.
  - Yellow, fixed: the test's `/usr/sbin/porelo-host` also matched env's echoed line, so it did not prove porelo's
    own words. It now asserts porelo's whole line, env's line once, and `handedOver` false with no answer.
  - Yellow, fixed: the changelog said "not with env's own lines"; env's lines still show, above porelo's.
  - The token stays out of every output on the new branch, and the borrowed input is still cleared.

## Acceptance

- The tests above, seen red, then green.
