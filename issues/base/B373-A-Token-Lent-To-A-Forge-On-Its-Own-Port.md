# B373 — A Token Lent To A Forge On Its Own Port

**Status:** Idea.
**Size:** S, proposed - the credential helper keyed by host and port, with a test against a forge on a port of its
own.

**What must be true.** A vault token declared for an `https://` repository on a port other than 443 is lent to git
for that repository. Today `FollowCredential` keys its credential helper by `GitCredentialNames.hostOf`, which ends
the host at the first `:`, so git is given `credential.https://<host>.helper` and, as measured with git 2.53, does
not match it against `https://<host>:<port>/`: the fetch goes out without the token and is answered as unreachable.
The loopback tests do not see it, because they rewrite the address they fetch.

- The helper's key names the port when the repository's address names one.
- A test fetches from a loopback forge on its own port without rewriting the address.

Found by a reading of [B368](README.md#b368) on 2026-10-31.
