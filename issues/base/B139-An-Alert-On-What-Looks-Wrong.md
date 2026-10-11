# B139 — An Alert On What Looks Wrong

**Status:** Analyzed.
**Size:** L - thresholds need a measured baseline first; three watchers, a contract event and containment.

**What must be true.** Sokar raises an alert on anomalies a person should see - very large prompt bodies, many clearance
attempts, unusual name patterns under declared domains - and a project chooses what an alert does: it only alerts, or it
also stops the task and revokes its token.

## Why

Today these are journalled (`reach.md`) and nobody is told while it happens.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §5/§6; Acalvio 7, 10, 11; ACSC (frequent repetitive prompts, a baseline); AISVS 9.1.3, 9.6.1, 9.6.3. From a review of security guidelines, 2026-10-09.

## Plan

Which signals, with which thresholds per project; the alert through the channels Sokar already has (a desktop
notification, the interface); stopping the task per project, off by default. Read in the code on 2026-10-11: every
signal is already seen on the host, per task, by a process that runs beside the task.

- **Large prompt bodies:** `VaultProxy.handle` (`supervisor/`) reads each request's body, up to `BODY_LIMIT`, before
  it forwards it.
- **Many clearance attempts:** `ShieldWatchCommand` (`apps/app-egress/`) receives every blocked connection from the
  NFLOG reader and asks through `ClearanceService`; each decision goes to `ClearanceJournal` (`clearance/`).
- **Unusual names under declared domains:** the task's resolver (`shield/.../DnsPolicy.java`, dnsmasq with its log);
  a name is unusual by its length, its entropy and the count of distinct names under one declared domain.
- **Containment exists:** `TaskPanic` stops tasks and their helpers; `TokenBroker.revokeAllFor` revokes a task's
  tokens. There is no freeze: a paused agent holds its session while its tokens' lifetimes run on, so a task is
  either left running with the alert or stopped.

Steps, in order:

1. **Baselines measured, not guessed:** a week of the VM's acceptance runs and of real tasks, the body sizes, the
   blocked attempts per minute and the names per declared domain, written into this issue. The thresholds are set
   from them, each as a project setting with a default (`alerts:` in the project file, read into `core/.../project`).
2. **One alert type, one path.** `Alert(task, signal, measured, threshold, at)` written by each watcher to the task's
   `alerts.jsonl` and sent to the daemon, which shows it by a desktop notification (as `DesktopPrompt` does) and to
   the interface as a new field of the task (`alerts`) in what `15-task.varlink`'s `Watch` answers, a contract
   addition the interface and the plugin read. `sokar task status` and `sokar task logs <task> alerts` show it.
3. **The three signals,** each in its watcher, each with its test: body size in `VaultProxy`, attempts in
   `ShieldWatchCommand`, names in the resolver's watcher.
4. **What an alert does, per project:** `alerts.on: alert | stop`, `alert` by default. `alert` only tells; `stop`
   does as `task stop` and revokes the task's tokens through `TokenBroker.revokeAllFor`, journalled with the alert.
   No other value is read: a project file naming another is refused with the two it may name.
5. **Docs:** `doc/reach.md` (what is told, not only journalled), `doc/project-file.md`, `doc/commands.md`.

Red first: a `VaultProxy` test with a body above a threshold of 1 KiB that is forwarded and alerts nothing today.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A project with `alerts.on: stop` whose task crosses a threshold has the task stopped and its token refused by the
  broker afterwards; with `alert` the task runs on and the alert is shown. Seen to fail: a stopped task whose token
  still reaches the provider.
- The documentation page that describes the area says it.

## To be checked

- Near [B26](README.md#b26) and [B38](README.md#b38): whether alerts go into [B26](README.md#b26)'s machine-wide record once it exists, or stay per task (Agent
  Core's). Which baselines are measured is step 1 of the plan.
