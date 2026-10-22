# B233 — A Host Service Reached By Name

**Status:** Backlog.

**What must be true.** A task reaches a service on its own host, such as a forge or a registry, by a name the project
declares, on 443, without the task reaching anything else on the host.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) has tasks
reach the host's forge and registry by name on 443. Today a task resolves only declared names, through its own
resolver, and the address it gets is what the host's DNS gives; Porelo has no entry for "this name is the host
itself". The one host service a task reaches is the git gate, bound to loopback and mapped in at one address and one
port (`LoopbackMapping`).

## To be checked

- First, measured: whether a rootless task reaches a service on the host's own LAN address through pasta, and on
  which address, and whether the gate's mapping is the pattern to follow.
