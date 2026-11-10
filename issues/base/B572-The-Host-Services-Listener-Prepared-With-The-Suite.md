# [B572](README.md#b572) — The Host-Services Listener Prepared With The Suite

**Status:** Implementing. Filed 2026-11-09 under ST291, from the targeted run of the porelo-host stack on the shared test host.
**Size:** S - one root-run preparation script beside the acceptance suite, called by the VM chain around the suite.

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
