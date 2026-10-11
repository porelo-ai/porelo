# B179 — A Code Beside Why A Release Cannot Go

**Status:** Idea.

**What must be true.** When a person's release of a held or refused message is answered `NOT_DELIVERABLE`, the reply
of `Release` in `45-messaging.varlink` carries a code beside its `detail` sentence - `NOT_IN_CONVERSATION`,
`REFUSED_BEFORE`, `UNCHECKED`, `NO_ADDRESSEE`, `PEER_NOT_ALLOWED`, `SIGNATURE_MISSING` - so a client can say what to do
for each case without matching on an English sentence that is no contract. The field is added, so a client that does
not read it sees no change.

Asked for by `sokar-frontend`'s [F117](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f117),
which shows the sentence as it is until then.
