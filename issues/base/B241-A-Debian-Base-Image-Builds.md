# B241 — A Debian Base Image Builds

**Status:** Backlog.
**Size:** S - one generated step in `Containerfile`, its test and a comment and a line of the page; every image rebuilds once.

**What must be true.** A project or repository whose base image is Debian (`python:3.13-slim`, trixie) gets its image
built, as the project file promises for any base with apt, apk or dnf.

## Why

Measured on `0.5.1~snapshot.307`, in Porelo's own step that installs curl, git, ssh and tmux:
- with no `package_sources`, the default Ubuntu mirror is written into Debian's
  `/etc/apt/sources.list.d/debian.sources`: "The repository 'http://azure.archive.ubuntu.com/ubuntu trixie Release'
  does not have a Release file";
- with `package_sources: ["http://deb.debian.org/debian/"]`, every `URIs:` line is replaced, the security stanza's
  too: "The repository 'http://deb.debian.org/debian trixie-security Release' does not have a Release file";
- `package_sources: []` is refused.

So no value builds that base. The default mirror belongs to Ubuntu only, and a replacement must leave a stanza whose
suites the mirror does not serve as it was.

## Plan

Read in `Containerfile`, `Project` and `doc/project-file.md`.

1. The step replaces every stanza's `URIs:` line in `/etc/apt/sources.list.d/*.sources` with the project's sources,
   by default Ubuntu's mirror, whatever the distribution; on Debian that writes Ubuntu's mirror into
   `debian.sources` and points `debian-security` at a mirror that does not carry it.
2. **A stanza's `URIs:` is replaced only where it names the same archive as the source**, by its last path segment
   (`/ubuntu` for `/ubuntu`, `/debian` for `/debian`), still one `RUN` continued with backslashes.
3. **Docs:** the comment in `Containerfile`, `Project`'s default and `doc/project-file.md`'s line.
4. **Red first:** `ContainerfileTest.leavesADebianStanzaAloneUnderTheDefaultUbuntuMirror`, running the generated
   rewrite with `/bin/sh` on copies of Debian's and Ubuntu's `.sources`; then
   `rewritesTheDebianStanzaButNotTheSecurityOneForADebianMirror`.

**Decided:**
- A source without an archive path, such as an apt cache, replaces every stanza's `URIs:` as today; one with a path replaces only the stanzas of that archive.

## Acceptance

- `python:3.13-slim` builds with no `package_sources`, and with `http://deb.debian.org/debian/` it builds and still
  reads `trixie-security` from Debian's security archive. Seen to fail first: both refusals measured today.
- `ubuntu:24.04` with the default still fetches from the Azure mirror.

## To be checked

- On the VM: `python:3.13-slim` and `ubuntu:24.04` built with both settings, trixie's stanza layout read, and the
  Azure mirror's `noble-security`.
