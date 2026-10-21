# B241 — A Debian Base Image Builds

**Status:** Idea.

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
