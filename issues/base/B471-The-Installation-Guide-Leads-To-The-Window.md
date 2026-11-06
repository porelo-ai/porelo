# B471 — The Installation Guide Leads To The Window

**Status:** Analyzed.
**Size:** S - one section in `README.md` and one in `doc/getting-started.md`, and a line under *Before you start*; no
code. Estimate 30 minutes.

**What must be true.** A person installing Porelo, on Linux or in a WSL distribution on Windows, finds the window in
porelo's own guide: its Linux package `porelo-frontend` from the same repositories, and on Windows the ZIP or the MSIX,
linked to the window's chapter on the site (`https://porelo-ai.github.io/frontend/#windows`), never to a repository's
address that changes.

## Found

The operator's test on a Windows computer with Porelo in WSL Ubuntu (2026-11-06): after installing Porelo by
`README.md` and `doc/getting-started.md`, nothing led to the Windows client.

- **`README.md`**, *Getting started*: the package lines, `porelo setup`, `porelo doctor`, a first task; no window.
- **`doc/getting-started.md`**: *Before you start* says "Porelo runs on Linux only" and that on Windows everything
  "would sit on the far side of a virtual machine", without saying that this is what a WSL distribution is and that
  the window on Windows reaches it there; *1. Install* names `porelo` and `porelo-agent-claude` only.
- **The window's chapter** (`porelo-frontend`'s `doc/index.md`, served at `https://porelo-ai.github.io/frontend/`,
  checked live: 200, with `id="windows"`) has both: `porelo-frontend` from the same `apt`/`dnf` repositories, and
  the Windows ZIP and MSIX with their checks. Nothing in porelo links to it.

Cause: the guide was written before the window had packages; the downloads went into the window's own chapter, and no
change since added a way there.

## Plan

- **`README.md`**, after the first commands of *Getting started*: a short paragraph, *The window*: on Linux
  `sudo apt install porelo-frontend` or `sudo dnf install porelo-frontend` from the repositories above; on Windows,
  Porelo in a WSL distribution and the window as a ZIP or an MSIX, both described with their checks in
  [the window's chapter](https://porelo-ai.github.io/frontend/#windows).
- **`doc/getting-started.md`**: under *Before you start*, one sentence that Windows runs Porelo in a WSL distribution
  of a supported system, the window on Windows reaching it there; under *1. Install*, a subsection *The window*, the
  same two ways, the same link.
- **Name the distribution:** `wsl --install` installs Ubuntu 24.04 unless told otherwise, whose podman 4.9 Porelo
  refuses (see *Before you start*). The sentence names Ubuntu 26.04 or Debian 13 as the distribution to install.
- **Checks:** `check-doc-site` and `check-readmes` as for any document; the link is an absolute address, which the
  site keeps as it is.

## Open questions

- None for this issue. Beside it, for the window's chapter (Agent Frontend's): its Debian lines install the armored
  `.asc` at `signed-by` with no fingerprint check, where porelo's guide dearmors it and installs it only when the
  fingerprint matches.
