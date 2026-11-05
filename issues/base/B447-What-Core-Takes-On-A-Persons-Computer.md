# B447 — What Core Takes On A Person's Computer

**Status:** Idea.
**Size:** M - a measuring program, a result file and a page; an arm64 build, which a Mac needs, is L by itself and is its
own issue when it comes to that.

**What must be true.** A person can read what their computer needs for Porelo Core, as a measured number each, with "not
measured" until then. The question is the overhead over running an agent directly on the machine: the VM and `porelod`,
and each task's container beyond the agent itself. An agent's or a build's own use is not the question, except that on a
Mac and in WSL they now live inside the VM.

## The Case

A machine of 32 GB with 5 tasks at once, realistically, and 10 at most. On a Mac and in WSL also: how much the VM must be
given for that, what stays for the desktop, and how a build's disk speed in the VM compares with the same build directly
on the machine.

The load is the one `HO125` uses, so that the desktop's half and the central machine's half compare in one table:
`porelo-buildtools` at a pinned tag, and the same fixed agent start.

## The Plan

A small program, of Core alone, needing nothing of the window. It runs in Linux, in WSL on Windows and in a Linux VM on a
Mac. It measures the daemon at rest, then a task with an agent, then with a build, then 5 and 10 tasks, by CPU, memory and
disk. It writes one result file a person can send us, holding numbers and no name, path or credential. The stub agent
needs no credential, so it can run on any machine; its agent's own use is then known to be small.

**How it runs:** a script beside the package, or inside it. The package is amd64 and the binaries are native, so a single
download is possible once the binaries are published by themselves.

**The VM's own share** is read from outside the VM where possible. Which process to read on each system is to be measured
first, not assumed.

## On A Mac

**The one real precondition: Core has no arm64 build.** Every job in `.github/workflows/build.yml` runs on an x86-64
`ubuntu` runner, and `dist/dist-deb/src/deb/control/control` says `Architecture: amd64`. So a Mac on Apple Silicon can run
none of this natively today. Needed first: arm64 native images, packages and task images, an arm64 runner, and the test
agent's binary. Running amd64 under emulation would measure the emulation.

**Which VM first: Lima with Ubuntu.** It has a supported system where a package installs as on any other machine. Lima's
documents say it uses Apple's `vz` by default on macOS 13.5 and newer since Lima 1.0, and name an arm64 Ubuntu image and
`virtiofs` mounts. `podman machine` comes second, as its image is Fedora CoreOS and read-only by the Coordinator's memory,
not read by me, so a package is awkward there. A blog says its default on Macs became `libkrun` in Podman 6.0, and other
pages disagree; that needs the release notes before anything rests on it.

Sources: [Lima's VM types](https://lima-vm.io/docs/config/vmtype/), and
[a blog on Podman 6.0](https://www.youngju.dev/blog/2026-07-17-podman-6-0-breaking-changes-libkrun-pasta.en).

## What We Have To Measure On

Linux: this machine and the shared VM, at their own size. Whether a 32 GB Linux machine, a Windows machine with WSL2 or a
Mac on Apple Silicon exists for us is not known to me: **the operator says**.

## Not Depending

Nothing of the window, nor of `F108` or `F109`, needs to be finished.
