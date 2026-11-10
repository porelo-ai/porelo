# B572 — The Host-Services Listener Prepared With The Suite

```json
{
  "$schema": "issue-1.json",
  "id": "B572",
  "title": "The Host-Services Listener Prepared With The Suite",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "A root-run script beside the acceptance suite prepares host-services' TLS listener, so the VM chain runs that feature instead of skipping it."
}
```

**Why this size.** one root-run preparation script beside the acceptance suite, called by the VM chain around the suite.

Filed 2026-11-09 under ST291, from the targeted run of the porelo-host stack on the shared test host.

**What must be true.** The acceptance feature `shield/host-services.feature` runs wherever the suite runs on a VM or a
rented machine, instead of being skipped because nothing prepared it.
- A script kept in porelo beside the acceptance suite prepares, as root, a TLS listener on exactly `127.0.0.1:443`
  whose certificate names `forge.host-service.test` and whose `GET /` answers `host-service-ok`, with its public CA
  readable at `/var/tmp/porelo-host-services/ca.pem`. The CA's key is deleted after signing.
- It refuses when something listens on port 443 already, and `down` removes the listener, the CA and the keys.
- The VM chain calls `up` before the suite and `down` after it, and sets `PORELO_ACCEPTANCE_HOST_SERVICES` on the
  suite runner only when `up` succeeded.

## Why

The feature says it needs that listener "prepared outside the suite", and no script of porelo's prepared it, so every
chain skipped it. On 2026-11-09 it ran for the first time, on the shared test host, from a script porelo-host's agent
wrote for that run (a transient systemd unit running a small Python TLS server, its certificate from a fresh CA whose
key is deleted after signing). The feature is porelo's, so its preparation belongs in porelo.

## Plan

- Take that script as the start: `acceptance/suite/src/test/host-services/listener.sh` (`up` / `down`), with the
  feature's description pointing to it.
- The VM chain's suite step: `up` as root on the machine before the suite, `down` after, the variable set only after a
  successful `up`.
- Tests by level: the script's refusal paths by a shell test against a fake `ss` and `systemd-run` on the path; the
  listener's answer only through the feature itself.

## As built

- `acceptance/suite/src/test/host-services/listener.sh` provides root-only `up` and `down`, starting from the
  transient-unit preparation. It refuses occupied TCP 443, failed socket inventories, an existing unit and
  leftover paths. Private material is created with restrictive permissions; the CA signing key is removed before
  launch, and only the public CA is readable by the suite. Failed preparation rolls back its own files and unit;
  a failed stop reports the files retained for cleanup.
- `HostServices` surrounds the leg's suite step using its existing root SSH connection, reconnected for cleanup
  after restart scenarios. Readiness reaches only the ordinary suite runner after successful preparation. Both
  one-account and parallel legs run restart scenarios last with a separate report. `Deploy` still installs only;
  it does not run a suite or leave this listener on a persistent machine.
- Script tests run copies with fixed machine paths redirected into test directories and fake commands on PATH;
  legs tests cover preparation, runner readiness, cleanup on failure and restart ordering. The TLS answer remains
  covered by `shield/host-services.feature`, whose description points to the script.
- No changelog entry: this is acceptance preparation only and changes no shipped artifact or package contents.
- The requested affected-module selection passed 53 tests with no failures, errors or skips. New tests were
  observed failing on behavior before implementation or restoration of deliberate breaks. A read-only review
  found no concrete issues. The real VM feature is not run in this restricted build environment.
