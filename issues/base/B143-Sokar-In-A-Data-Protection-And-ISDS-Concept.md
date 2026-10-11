# B143 — Sokar In A Data Protection And ISDS Concept

**Status:** Analyzed.
**Size:** S - one new page, a link and a navigation entry, and one documents test.

**What must be true.** A documentation page says what Sokar provides technically for a data protection and information security concept, what the organisation still has to regulate, and what to back up (the vault file, the journal).

## Why

Authorities' guidance asks an organisation for such a concept; Sokar's part of it is spread over several pages.

**Guideline points it answers:** Swiss authorities' guidance sheets; BSI catalogue 2.1. From a review of security guidelines, 2026-10-09.

## The shape

A page in `doc/`, linked from `corporate-security.md`.

## Plan

**Today.** `doc/corporate-security.md` answers a security officer's questions; the parts of a concept are spread over
`security.md`, `reach.md`, `credentials.md` and `running.md`. The files to back up are given by the path classes in
`apps/app-base`: the vault `vault.bin` in the data directory with its `.old` copy (`VaultPaths.vaultFile`); in the
state directory `stops.log` (`SokarPaths.stopJournal`), `failures.log` (`SokarPaths.failureLog`) and each task's
clearance journal `clearance/<task>.jsonl` (`TaskPaths.clearanceJournal`, renamed and kept at removal); the
messaging key and allowed signers (`MessagingPaths`); a mirror through `sokar gate backup`.

**Steps:**

1. Red first: `DataProtectionDocumentTest` in `apps/app`, tagged `documents`, that the page names each of the files
   above by the name the path classes give, and that `corporate-security.md` links it.
2. `doc/data-protection.md` in three parts: what Sokar provides (where prompts go, credentials kept on the host,
   egress denied by default, the gate, the journals, the `offline` class and a local provider); what the organisation
   regulates (the provider contract and where its data is kept, who operates a node, how long journals are kept,
   the incident process, device management); what to back up, with the paths, and that the vault is worth nothing
   without its passphrase or a keyslot.
3. The link from `corporate-security.md` and an entry in `mkdocs.yml` beside it.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## Open questions

- Whether the page follows the headings of one named guidance, or its own (the operator's).
