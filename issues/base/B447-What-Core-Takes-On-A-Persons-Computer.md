# B447 — What Core Takes On A Person's Computer And A Central Host

**Status:** Idea.
**Size:** L - M for the measuring program, its result file and the one table; M for the central host's runs, which reuse
it; M to L for what an arm64 build needs ready before a rented Mac's day. Nothing is built before the test.

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

A small program, of Core alone, needing nothing of the window. It runs in Linux, in WSL on Windows, in a Linux VM on a
Mac and on a central host. It measures the daemon at rest, then a task with an agent, then with a build, then 5 and 10 tasks, by CPU, memory and
disk. It writes one result file a person can send us, holding numbers and no name, path or credential. The stub agent
needs no credential, so it can run on any machine; its agent's own use is then known to be small.

**How it runs:** a script beside the package, or inside it. The package is amd64 and the binaries are native, so a single
download is possible once the binaries are published by themselves.

**The VM's own share** is read from outside the VM where possible. Which process to read on each system is to be measured
first, not assumed.

## In WSL On Windows, By One Command

The operator's word: they run this themselves, by hand, on a Windows machine with WSL, and everything runs with one command.

    curl -fsSL <the published address>/porelo-measure.sh | bash

**The command is typed in WSL alone; no line is needed on the Windows side,** as long as WSL's interop is on. The address is
fixed when it is published, in the way [B445](README.md#b445) publishes the test agent, by version and with its digest.

- **It asks once, at the start:** `sudo -v`, as a package needs it, and nothing on the way. It takes no token, since the stub
  agent needs none.
- **It names what is missing before it starts,** and installs what a package can bring. What it cannot change by itself it names
  and stops at: an Ubuntu older than 26.04, as 24.04's podman is too old, or systemd off in WSL's settings.
- **WSL's own share:** the VM's view from inside by `/proc/meminfo` and `df`, and Windows' view through `powershell.exe`
  by interop, reading the WSL VM's process. Which process, and that interop reaches it, is from memory and to be tried first. If
  interop is off, it prints the one line to run in Windows and goes on.
- **It cleans up what it made,** and only that: its tasks, images and directories, and the package if it was the one to install
  it. All of its state lies in directories of its own, every XDG one, so a person's Porelo is not touched.
- **It ends by printing where its one result file lies.**

## On A Mac

**Core has no arm64 build.** Every job in `.github/workflows/build.yml` runs on an x86-64 `ubuntu` runner, and
`dist/dist-deb/src/deb/control/control` says `Architecture: amd64`. Running amd64 under emulation would measure the
emulation.

**Which VM first: Lima with Ubuntu.** It has a supported system where a package installs as on any other machine. Lima's
documents say it uses Apple's `vz` by default on macOS 13.5 and newer since Lima 1.0, and name an arm64 Ubuntu image and
`virtiofs` mounts. `podman machine` comes second, as its image is Fedora CoreOS and read-only by the Coordinator's memory,
not read by me, so a package is awkward there. A blog says its default on Macs became `libkrun` in Podman 6.0, and other
pages disagree; that needs the release notes before anything rests on it.

### The Mac's Day

The operator's word: a rented Mac runs for a whole day anyway, so one rental carries all of it, in this order, as each
step decides the next:

1. **[B251](README.md#b251)'s firewall question.** It decides everything after: if the task firewall cannot load in that VM,
   Porelo cannot run there as it is, and the rest of the day is a different day.
2. **A complete arm64 build in the VM, by Maven,** with no GitHub runner: `porelo`, the filter and one agent.
3. **The measuring,** on that build.

### What Must Be Ready Beforehand

So that a rented day does not go to preparing, each as a change on a branch that leaves amd64 as it is:

- `pom.xml:105-106`: `musl.home` and `musl.gcc` name `x86_64-linux-musl`. The hooks link statically against it
  (`hooks/pom.xml:58-59`), and the build tools' `musl` command fetches the toolchain. Needs an aarch64 toolchain, pinned.
- `pom.xml:544` asks `-march=x86-64`, and `porelo-cpu-check` (`pom.xml:424`) holds every image to that exact set. Needs an
  aarch64 baseline, and the check told of it.
- `dist/dist-deb/src/deb/control/control:8`, `dist/dist-rpm/pom.xml:85`, `agents/pom.xml:230`, and the file names at
  `dist/dist-deb/pom.xml:101` and `agents/pom.xml:147`.
- The JDK: `.github/actions/pinned-jdk` and the file named at `porelo.yml:15` are `linux-x64`. Needs the aarch64 archive
  with its digest. Whether the same GraalVM build exists for aarch64 is to be read.
- `mvnw:127-130` falls to `linux-amd64`.
- `.github/workflows/build.yml:390` and `:469` for the index, which matter only when arm64 is published, not on that day.
- Pinned digests: a base image and an agent's own pins, each read for an arm64 form. The one agent should be chosen by
  which has one. Not yet read.

**Tried first on a local machine, as far as it goes:** the property changes and the failing build's first faults can be
reached in an aarch64 VM under emulation (`qemu-system-aarch64`), on a much slower build; that is untried here and shows
nothing of speed. [B251](README.md#b251)'s sysctls and AppArmor can be compared in an arm64 Ubuntu image the same way, but
not `vz` itself, which needs the Mac.

### Its Rental, For The Operator's Word

Apple's licensing makes the least lease 24 hours, by Scaleway's documents. Scaleway's list, as a search returned it from its
pricing page and not read by me on the page: Mac mini M4-S, 16 GB, 256 GB, 0.22 EUR an hour; **M4-M, 32 GB, 1 TB, 0.29 EUR an
hour, so about 7 EUR for the least lease**, and the case's 32 GB. Whether `vz` runs there, as on any Mac mini, is to be read
first. AWS's current price for a Mac host was not found.

## The Central Host

Moved here from `HO125` by the operator's word, so that one issue holds Linux, WSL, a Mac and the central host, with one load
and one table. What stays in that issue is Agent Machines' to say.

**The question, the operator's:** how many developers, each with about 5 tasks, fit sensibly on a host of 64 GB and of 128 GB,
with and without Forgejo? The number of tasks is raised until something suffers, and the answer names what suffers first:
memory, CPU, disk or the half for jobs.

**The machines, as `HO125` read them on 2026-11-05:** `ccx43` at Hetzner, 16 dedicated vCPUs, 64 GB, 360 GB, 0.4423 EUR an hour
net; `EM-I220E-NVMe` at Scaleway, bare metal, 128 GiB. Its reasoning stands: rent both, the smaller first, as what a task takes
adds up and can be read across, and what suffers under load cannot. Their rental stays for the operator's word.

**The setup, proposed by the Coordinator and checked by Agent Machines by reading only; none of it run on a host.** `HO125` stays
as the tool: the `task`, `agent` and `job` readings of `porelo.host.measure`, and the throwaway accounts. `up` in the shapes `room`
and `both`; 5 tasks each; the stub agent for the mass; developers raised until something suffers, 1, 2, 4, 8, 12 at most, read
from the cgroups and PSI. As checked:

- A vault comes up with no input, by `--passphrase-stdin`.
- `up` needs a stand-in organization to follow, which need not exist, as `measure` has it.
- The accounts are made by the run itself, as `porelo-host-person` does. That the follow leaves them alone is unshown.
- Tasks stand in `default`, as the local forge needs a followed project, which registers it. A job's load gets one repository on the
  forge, made through Forgejo's admin. A gate user's share stays "not measured".
- **`default` has no project naming the host's gate, so each account runs its own homeserver, where a real host has one.** It is read
  from its own unit and taken out, and the table says so, or the room reads too high.
- One real agent at a fixed start needs a credential, so a token in a throwaway vault on a rented machine, which is the operator's
  word, or that row stays "not measured".
- **Before any rental:** one such account on a local VM, shape `room`, two stub tasks, about half an hour, by Agent Machines' estimate.

**A build at once does not run in `default`:** read in `DefaultProject.java` and `doc/getting-started.md`, Default opens no egress
beyond what the agent needs, so no Maven Central. The JDK comes from `porelo-buildtools`' own `porelo.yml`, which a start in a checkout
reads. So the build's load needs a project of its own on this machine, with the `maven` set, in every setting of this issue. How a
run makes it with no organization and no question is to be settled, and tried first. The stub agent's version is pinned once
[B445](README.md#b445) has moved it.

Sources: [Lima's VM types](https://lima-vm.io/docs/config/vmtype/),
[a blog on Podman 6.0](https://www.youngju.dev/blog/2026-07-17-podman-6-0-breaking-changes-libkrun-pasta.en),
[Scaleway's Apple silicon pricing](https://scaleway.com/en/pricing/apple-silicon), and
[its documents on creating a Mac mini](https://scaleway.com/en/docs/apple-silicon/how-to/create-mac-mini),
and for the Hetzner figures [a report on June 2026's rise](https://wz-it.com/en/blog/hetzner-price-increase-june-2026-cpx-ccx-alternatives/).

## What We Have To Measure On

**Linux:** a server rented at Hetzner, by the operator's word. Each has dedicated vCPUs and no desktop beside it, so what stays for
a desktop is left to the reader. Each rental stays for the operator's word, with its price.

| Row | Type | vCPUs | Memory | Monthly, EUR, without IPv4, from a third party |
| --- | --- | --- | --- | --- |
| 8 GB | `ccx13` | 2 | 8 GB | 42.99 |
| 16 GB | `ccx23` | 4 | 16 GB | 85.99 |
| **32 GB, the case** | `ccx33` | 8 | 32 GB | 138.49 |

8 GB is worth a row as the smallest a person may ask about, and 16 GB as a common laptop. **The price per hour is not read from
Hetzner's API,** which needs a token this issue's writer does not hold; these are third parties' figures after June 2026's rise, and
they disagree by source. The API's figure goes here before the operator is asked, as `ccx43`'s did.

**Windows:** the operator's own machine with WSL, by hand. **Mac:** a rented day, as above.

## Not Depending

Nothing of the window, nor of `F108` or `F109`, needs to be finished. The host's half uses Agent Machines' `porelo.host.measure`
where that holds what it needs.
