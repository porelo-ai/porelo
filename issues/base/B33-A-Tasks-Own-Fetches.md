# B33 — A Task's Own Fetches

**Status:** Analyzed.
**Size:** S - a reproduction on the VM first; at most one image line and one acceptance scenario follow from it.

**What must be true.** A task can fetch from the forges its work depends on, and a failure to do so is never
reported as a credential problem.

## Why

Taken from a fix in the reference implementation; the failure it describes has not been reproduced on this
project's images, and the first job is to reproduce it rather than to act on it.

### What they found

> GitHub answers most anonymous git-over-HTTPS requests from noble's git/libcurl-gnutls client with
> 401, while the same request over HTTP/1.1 is served.

Their fix is one line in the image, and only on noble:

```
git config --system http.version HTTP/1.1
```

### Why it would apply here

The default base image for a task is `ubuntu:24.04`, which is noble, and nothing in this project
sets `http.version`. Porelo's own gate is not affected — an agent pushes to it over plain HTTP on a
link-local address — but **anything the work itself fetches is**: a dependency from a forge, a
`pip install` from a git URL, an online-class project cloning its real upstream.

**The symptom is the dangerous part.** It arrives as `401`, which reads as a credential problem. In
a product whose whole subject is credentials that deliberately never enter the container, an
unexplained 401 inside a task is the most expensive possible wrong signal: every instinct points at
the broker, the phantom token, or the vault, and none of them is involved.

**Reproduce before fixing.** This is a report about somebody else's images, and the difference
between their base and this project's is unmeasured. A one-line change that is not needed is a line
nobody can delete later, because nobody knows what it was for.

**This project has been bitten by an HTTP version once already.** `HttpClient` negotiating HTTP/2
and exposing its pseudo-headers is in AGENTS.md: it surfaced as `Unable to connect to API` after 21
responses that were all HTTP 200. Different mechanism, same shape - a protocol difference wearing
an authentication failure's clothes.

## Plan

Read in the code at `30c8169b`. The default base image is `ubuntu:24.04` (`DefaultProject.BASE_IMAGE`, and
`AgentLogin.LOGIN_BASE_IMAGE` for the login container); a project may name any image with apt, apk or dnf
(`image.base_image`). The image's Containerfile is written by `Containerfile` (`runtime/.../Containerfile.java`), which
installs `git` with `curl` and `ca-certificates` and sets no `http.version` anywhere.

Steps:

1. **Reproduce (no code).** On the VM, an `online` task on `ubuntu:24.04` whose project declares `github.com`
   (the `git-hosting` set): `git clone https://github.com/<a public repository>` inside it, with and without
   `git config --system http.version HTTP/1.1`; the same on a Fedora base. The result goes into this file. If it
   does not reproduce, this file says so and is closed with nothing built.
2. **If it reproduces:** an acceptance scenario in `acceptance/suite/.../task/`, red first, cloning a public
   repository inside a task on `ubuntu:24.04`. Then one `RUN` in `Containerfile`, applied only where `/etc/os-release`
   says noble, with the reason in a comment beside it and in the class's Javadoc. A second scenario on a Fedora base
   proves the line is not applied where it is not needed.
3. **The wrong signal.** Whatever the result, a 401 from a forge inside a task never reaches Porelo's broker - an
   anonymous clone carries no phantom token - so nothing of Porelo's can mislabel it. A line in `doc/running.md`,
   under adding your own tooling, says that a 401 on an anonymous fetch is not the vault's, and names the setting if
   step 1 found it.

## Acceptance

- An anonymous clone from a public forge succeeds inside a task on every supported base image. Seen to fail: the
  clone, run inside a task on a base image without the setting it needs, returns 401.
- Where a base image needs a setting to achieve that, the image carries it and the reason is
  written where somebody changing the image will read it. Seen to fail: removing the setting from that image
  makes the clone above fail.
- The setting is applied to the images that need it and not to the ones that do not - a workaround
  applied everywhere is one nobody can later remove safely. Seen to fail: a check of the images finds the
  setting on one where the clone succeeds without it.
- A fetch that fails for a protocol reason does not present as an authentication failure. Seen to fail: a fetch
  forced to fail on protocol is reported as a 401 or a credential error.

## To be checked

- **Whether the acceptance suite should depend on github.com** (Agent Core's). The scenario is one a person would
  do, but it makes the suite's result depend on a forge's behaviour; a scenario run only on request is the other
  way.
