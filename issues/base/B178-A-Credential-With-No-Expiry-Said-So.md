# B178 — A Credential With No Expiry Said So

**Status:** Accepted.

**What must be true.** Storing a credential that carries no end - by `vault put`, `vault login` or `ImportCredential` -
says so when it is stored, rather than refusing it, since some providers issue nothing else. The credential list
(`vault list`, `Credentials()`) marks such an entry, so the one long-lived secret left stays visible to whoever can
replace it with a shorter-lived one.
