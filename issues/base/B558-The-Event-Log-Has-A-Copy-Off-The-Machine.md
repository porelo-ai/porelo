# [B558](README.md#b558) — The Event Log Has A Copy Off The Machine

**Status:** Backlog, priority Medium. Split off from [B154](README.md#b154) on the operator's word of 2026-11-09, as its
part 2; for after the move-4 test.
**Size:** M - a setting, a sender that never blocks an event, its failure named by `doctor`, and the documentation.
**Blocked by:** [B154](README.md#b154).

**What must be true.** Where the account-wide `config.yml` names a destination (`event_log: {copy: <destination>}`,
absent by default), each line appended to the event log is also sent off the machine, so the record does not hang on
this machine alone. Without one, nothing leaves. A send that fails never blocks or loses the event: `doctor` names it,
and a later send resumes from the log's position. `doc/settings.md` gives the key, and `doc/security.md` says what the
copy does and does not prove.

## Why

An account's own file cannot be made tamper-proof against the account. The journal anchor shows that a line was
changed, but a person who controls the machine controls both. A copy the account can append to but not rewrite, held
elsewhere, keeps the record when the machine itself is in question.

## Open questions

- Which transport: syslog over TLS, an https endpoint, or an S3 Object Lock bucket written with a key that cannot
  delete. That choice, and what a person must run to receive it, is the operator's.

## To be checked

- Whether a send that failed is retried from the log's position, or the gap is only named.

## Acceptance

- Seen to fail first, then green: with a destination set, each event reaches it; without one, nothing leaves the
  machine; a destination that refuses is named by `doctor` while events keep being written.
