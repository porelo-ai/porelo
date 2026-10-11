# B59 — A Kernel Of Its Own For A Task

**Status:** Analyzed.
**Size:** XL - an experiment, a doctor probe and an adoption that would change the hooks; split as the plan says.

**What must be true.** Whether a task can run under a runtime that gives it its own kernel is measured rather than
assumed, and a task never starts that way without its ruleset failing closed.

## Why

Nice to have; nothing waits on it, and nothing in the default path changes until the first two questions below
have measured answers.

A task is a rootless container: every capability dropped, `no-new-privileges` set, an unprivileged
account inside. What it shares with the machine is the **kernel**. That is stated plainly in
`doc/how-it-works.md`, and it is the one boundary Sokar cannot make stronger by dropping
anything further: a kernel bug is one boundary away from the host.

A container can also be started with a runtime that puts it in a virtual machine of its own -
`podman run --runtime krun`, backed by libkrun and KVM. The image stays an OCI image, the workload
stays a container to everything above it, and underneath it gets its own kernel, with the
hypervisor as a second wall. **Whether Sokar's containment survives that move is unmeasured, and
this issue exists to measure it rather than to assume either answer.**

## What was measured, 2026-09-16

On the machine this was written on:

- `/dev/kvm` exists, and podman's runtime is `crun` 1.21.
- The distribution packages a KVM-backed OCI runtime (`crun-krun`, `libkrun`, `libkrunfw`), so an
  experiment needs no build from source. Other runtimes of the same kind are packaged as well.
- Both libvirt test machines were powered off, so nothing was measured there. They are themselves
  virtual machines, and so are the rented ones: **nested virtualisation is a precondition on every
  machine Sokar is currently tested on**, and it is unknown whether it is available there.

## The shape: what the experiment has to answer

The first two decide whether the rest is worth doing.

1. **Where does the ruleset go?** The `nft` hook runs at `createRuntime` and loads a default-deny
   ruleset with `nsenter --target <pid> --net` - into the workload's network namespace on this
   machine. Under a kernel of its own there is no host pid for the workload and no host namespace
   holding its network stack. Either the filtering point moves to the host side of the virtual
   interface, or the ruleset moves inside the guest and something in there has to load it before the
   agent runs - and fail closed if it cannot.
2. **Can the resolver still lie?** `NXDOMAIN` for every undeclared name, and the resolver filling
   the nftables set for the declared ones, both depend on dnsmasq running in the same namespace as
   the workload. Measure what that becomes, including whether the guest resolves over TCP only.
3. **Do the hooks still run, and what can they still do?** Three of Sokar's four hooks reach inside
   the workload's namespaces. A hook that runs on the host while the workload runs under another
   kernel is a different instrument, whatever the runtime reports.
4. **Do the sockets still arrive?** The vault proxy, the ssh-agent and the clearance socket are unix
   sockets bind-mounted into the container, and the credential swap depends on them. Measure whether
   they reach the workload, and through what.
5. **What replaces `task attach`?** That runtime does not support `podman exec`, which is exactly how
   a terminal is handed over today (`exec -it … tmux new-session -A`).
6. **What does it cost?** Start time per task, memory per task, whether the image has to change, and
   whether the acceptance suite passes unchanged against a task started this way.
7. **Can a task be frozen and brought back?** Snapshot and restore of a task, its running agent with
   it, is a goal: a task would then come back after a restart where it was, not only through `task
   start --restarted` and the conversation [B46](README.md#b46) brings back. Measure whether the
   runtime can snapshot a running workload and restore it, on the same machine and after a reboot,
   and what podman lets through of it.

**What the second wall is worth is decided on these measurements, not before them.** The gain is
against kernel bugs; rootless, no capabilities and `no-new-privileges` already remove the usual
ladder. Whether that gain is worth what it costs is judged on the answers to a kernel of its own
working, the ruleset failing closed, and the cost - and on what question 7 adds.

## Plan

Read in today's code. `Podman` (`runtime`) starts every task with the host's default runtime; no `--runtime` is
passed anywhere. `NftHook` (`hooks`) runs at `createRuntime` and loads the ruleset with
`nsenter --target <pid> --net nft --file -`; `SupervisorHook` starts the resolver in the same namespace; the vault
proxy, ssh-agent and clearance sockets are bind-mounted from the runtime directory; `task attach` is
`podman exec -it ... tmux new-session -A`. `SecurityClass` (`core`) has `offline`.

It splits into three issues:

1. **The experiment** (M): first whether the libvirt and rented machines offer nested KVM (`/dev/kvm`, the CPU
   flags). Then, on a machine that does, the stub's image under `podman run --runtime krun`, by hand and with
   Sokar's hooks registered; questions 1 to 7 answered here, each with its command. No code changes. The
   measurements are the input to the decision on adoption; nothing is adopted before they exist.
2. **The doctor probe** (S): `sokar doctor` says whether `/dev/kvm` is usable and the runtime is installed. Red
   first: the probe's test on a machine description without `/dev/kvm` expects "not able".
3. **Adoption** (L), decided on the experiment's answers and only if questions 1 and 2 fail closed: a project
   setting that chooses the runtime, the `offline` class first; `Podman` passes `--runtime`; the ruleset in its new
   home; an acceptance leg on a KVM machine. Red first: a task whose ruleset load is made to fail starts under the
   KVM-backed runtime.

## Acceptance

- Every question above has an answer measured on a machine with KVM, written down with the command
  that produced it - including the ones whose answer is "this cannot work as it stands". Seen to fail: a question
  above with no measured answer and command beside it.
- **The default does not move.** A task keeps its rootless container with the nftables ruleset until
  questions 1 and 2 have an answer that fails closed, and adoption is then **opt-in per project**. Seen to fail: a
  task in a project that has not opted in starts under the KVM-backed runtime.
- `sokar doctor` says whether this machine can do it at all, rather than a task failing to start
  with a runtime error. Seen to fail: `sokar doctor` on a machine without `/dev/kvm` or the runtime reports it
  able.
- Snapshot and restore are measured: whether a task frozen with its running agent comes back, and what of it
  survives a reboot. Seen to fail: question 7 with no measured answer and command beside it.
- The `offline` class is the first candidate: it declares no egress, so question 1 is smallest there.
- No acceptance scenario is weakened to make a task start this way, and a task that cannot get its
  ruleset does not start - under either runtime. Seen to fail: a task whose ruleset load is made to fail starts,
  under either runtime.

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F8,
answering OpenSSF tip 2, OWASP AI Agent Cheat Sheet §9.

## To be checked

- **Nested virtualisation** on the libvirt test machines and on rented ones (Agent Core's, measured). Without it,
  this cannot be tested where everything else is tested, and that alone may end the experiment.
- **Whether the guest is the better home for the ruleset anyway** (Agent Core's). A ruleset the workload cannot
  reach around, loaded by something the workload cannot stop, is the property that matters; which kernel holds it
  is not.
- **This is the rehearsal for `sokar-project` [PJ25](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj25)**, Sokar on Apple Containers (Agent Project's). A container on
  macOS always has a kernel of its own, so that requirement asks the same questions: where per-container egress
  filtering lives and whether it fails closed, whether a host socket reaches the workload, whether the host's
  loopback can be mapped in for the gate, and how much is genuinely shared. Answering them here costs one Linux
  machine with KVM and the acceptance suite that already runs.
