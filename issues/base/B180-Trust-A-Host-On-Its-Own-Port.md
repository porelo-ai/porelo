# B180 — Trust A Host On Its Own Port

**Status:** Backlog.
**Size:** M - one way to name a host with its port in `HostKeys`, used by the command, the start check, the offer at
task start, the workspace set-up, the reconcile and the daemon's two host calls, unit tests for every form of address,
and an acceptance case against an sshd of the scenario's own on a high port; the daemon's contract keeps its shape.

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

## Plan

Read in today's code:

- `HostKeys.offeredBy` runs `ssh-keyscan -T 10 <host>`, which asks port 22. `HostKeys.trust` writes the line the scan
  printed. `HostKeys.known` looks for the name it is given among the names in a line's first field.
- `StartCheck.unknownHostOf` already builds `[host]:port` for an `ssh://` address with a port other than 22 and asks
  `known` for it, but it returns the bare host. Every hint and the offer at task start are built from that:
  `TaskLaunch.launch` and `trustUnmet`, and `TaskMethods` for `CanStart`'s `host` and `hostKeys`.
- `WorkspaceSetup` and `Reconcile` take the host for their hint from `GitCredentialNames.hostOf`, which drops the port.
- The daemon's `HostKeys` and `TrustHostKey` in `20-project.varlink` take `host: string`. `CanStart` answers `host`
  "spelled exactly as TrustHostKey wants it, so a client passes it back unchanged".

The plan:

- **One name for a host on a port:** `host` on port 22 and `host:port` on any other, the form a person types and every
  hint prints. A port of 22 written out is the same as none. An address in brackets (`[2001:db8::1]:7999`) is read as
  ssh writes it, so an IPv6 address is never split at its colons.
- **In `HostKeys`:**
  - `nameOf(upstream)` returns that name for an `ssh://` address, a scp-style one (always port 22) or `null`. It
    replaces the reading of host and port in `StartCheck.unknownHostOf` and the `GitCredentialNames.hostOf` calls in
    `WorkspaceSetup` and `Reconcile`.
  - `offeredBy`, `trust` and `known` take that name. `offeredBy` adds `-p <port>` to the scan, which then prints
    `[host]:port` in each line itself, so `trust` writes what ssh looks up without building it. `known` looks for
    `[host]:port` for a port other than 22 and for the bare host for 22, by the names in the first field, exactly, as
    today.
- **`sokar credentials trust-host <host>[:<port>]`:** no `--port`, so the hint can be printed as it is and typed back.
  The help and the reference page say which ports need it. Its output names the host with its port.
- **The daemon:** `HostKeys` and `TrustHostKey` take the same name in their `host: string`, and `CanStart`'s `host`
  carries it, so a client that passes it back unchanged already works. Only the comments in `20-project.varlink` grow,
  to say the port is part of the name; no field is added.
- **What stays out:** a host whose port comes only from an alias in `~/.ssh/config` is resolved by ssh and not by this,
  as `20-project.varlink` already says for the host name.

Steps:

1. **Red first:**
   - `HostKeysTest`, extended with a runner that records the scan:
     - `nameOf` for `ssh://git@forge.example.org:7999/p/r.git`, for `ssh://git@forge.example.org:22/...`, for
       `ssh://git@forge.example.org/...`, for `git@forge.example.org:p/r.git` and for `ssh://git@[2001:db8::1]:7999/...`;
     - the scan asked with `-p 7999`;
     - `known` true for a line `[forge.example.org]:7999 ...`, and false for `forge.example.org ...` asked as
       `forge.example.org:7999`, and the other way round.
   - `StartCheckTest` with a 7999 upstream expects the hint `sokar credentials trust-host forge.example.org:7999`.
2. `HostKeys`, the command, `StartCheck`, `TaskLaunch`, `WorkspaceSetup`, `Reconcile` and the daemon's two calls as
   above; `doc/commands.md` says the port for `trust-host`.
3. **Acceptance:** in `credentials-commands.feature`, a scenario starts an sshd of its own as the account, on a high
   port with a host key made for it. It then checks three things:
   - `trust-host localhost:<port>` shows that key's fingerprint and not the machine's own;
   - with that fingerprint the key is recorded under `[localhost]:<port>`;
   - the start check of an `ssh://` upstream on that port no longer says the machine has never met it.
4. Reactor, documents and the suite on the VM (`not @restart`).
