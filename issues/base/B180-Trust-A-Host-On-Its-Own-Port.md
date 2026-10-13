# B180 — Trust A Host On Its Own Port

**Status:** Idea.

**What must be true.** A task whose upstream speaks SSH on a port other than 22 (`ssh://git@forge.example.org:7999/...`,
the usual port of a self-hosted forge) can be started after the person trusts that host once, the same way as one on
port 22:

- `sokar credentials trust-host` takes the port (`forge.example.org:7999`), asks the host on that port what it offers,
  and records the key under the name ssh looks up for it, `[forge.example.org]:7999`.
- The question whether a host is known matches that name for a port other than 22, and only the bare name for 22.
- Every hint that sends the person to `trust-host` - at the start check, the offer at task start, the workspace set-up
  and the reconcile of a project - names the port the upstream uses, so following it lets the task start.
- The offer at task start asks the host on the port of the upstream, not on 22.

Today the start check already looks for `[forge.example.org]:7999`, but `trust-host` asks port 22 and records the bare
name, so the entry it writes never answers the check and the hint it gives is a dead end - or, when another service
answers on 22, records a key that belongs to that one. Found in a test against a self-hosted forge.
