# B188 — No Search Domains Of The Host In A Task

**Status:** Review.
**Size:** S - one option on the container, a test of the arguments, and a check of the task's `resolv.conf` in the
acceptance suite.

**What must be true.** A task's `resolv.conf` names no search domain. A name the agent looks up is asked as it is
written, not once per search domain of the host.

## Why

A security analysis on a test machine found the container with the host's DNS search domains. A name like
`example.com` was asked as `example.com.corp-a.example`, `.corp-b.example` and `.corp-c.example` too. Two things
follow:

- The task learns internal domain names of the place it runs in.
- Each refused name costs four lookups and four prompts' worth of noise.

Nothing left the machine (NXDOMAIN), but nothing about a task needs those domains.

## Plan

Read in today's code: `ContainerSpec` passes `--dns <address>` for the task's own resolver and no `--dns-search`, so
podman copies the host's `search` line into the container's `resolv.conf`. The task's resolver (`DnsPolicy`) already
ignores the host's `resolv.conf` for its upstreams.

The plan:

- **The option:** `ContainerSpec` adds `--dns-search=.`, podman's way of saying no search domain, beside `--dns`
  for every task that has its own resolver - every class but one run without a network.
- **Short names:** a name without a dot is no longer completed. Inside a task nothing relies on that: the gate and
  the proxy are reached by address.

Steps:

1. **Red first:** `ContainerSpecTest`: the arguments hold `--dns-search=.` next to `--dns`.
2. The option.
3. **Acceptance:** in `task-inside.feature`, the first scenario's shell shows no `search` line in `/etc/resolv.conf`.
4. Reactor, documents and the suite on the VM (`not @restart`).

## As built

`ContainerSpec.toArguments` adds `--dns-search=.` once, when the task has a resolver of its own, so a container
without one gets nothing about DNS.

