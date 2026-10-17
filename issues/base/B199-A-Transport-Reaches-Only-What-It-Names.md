# B199 — A Transport Reaches Only What It Names

**Status:** Accepted.

**What must be true.** A transport's adapter reaches only the hosts its `setup` names in `reaches`, as
`doc/transports.md` promises ("Porelo lets the transport reach those and nothing else") - or the page says what holds
instead.

**Why.** Read in the code on 2026-10-17: Porelo reads `reaches` in one place, `TaskConversations` (`app-messaging`),
to refuse an offline project whose conversation leaves loopback. Nothing builds a rule from it. The adapter runs on the
host as the account's own process, with the account's whole network, so a transport could reach any host. Found
answering porelo-message-matrix's question on a host without a port in `reaches`.
