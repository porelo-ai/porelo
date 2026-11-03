# B411 — A Provider's Refusal Reaches The Person

**Status:** Idea.
**Severity:** medium: a failed start hides why it failed, and the person looks in the wrong place; nothing leaks.

**What must be true.** A provider's error that can be checked for a credential reaches the person, so they read why the
provider refused. An answer the proxy truly cannot check stays withheld.

**What holds today.** Measured in a walk: GitHub Copilot answered a model the account lacks with a 400 whose body is JSON
carrying `model_not_supported`, labelled `text/plain`. The proxy passed on only "the provider answered in a form this proxy
cannot check for credentials (upstream 400, content-type text/plain)", so the person never read the reason.

Read in the code: `VaultProxy.whyUnreadable` refuses every declared media type outside `application/json` and
`text/event-stream`, without looking at the body. An answer declaring no type is already read by its first bytes
(`startsAsJsonOrEvents`).

**To be checked.** Whether a declared `text/plain` that opens as JSON can be held to the same scan as `application/json`, for
every answer or only for an error status, and whether a body that opens as JSON and then stops being JSON can carry a credential
past the scan. The refusal is a security rule, so a change to it gets a reading.
