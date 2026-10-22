# B247 — A Session Directory Outside The Home

**Status:** Implementing.
**Size:** S - one check in the agent API's `SessionIds` and one path in `TaskSession`, their tests and a line of `agents/README.md`; the agent API only grows.

**What must be true.** An agent definition's `session.session_id.directory` may be an absolute path in the container,
and Porelo finds the newest session file there as it does under the agent's home today; a relative one still means
"under the home".

## Why

From porelo-pi [PI21](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi21): Pi's own directory moves
to a root-owned `/var/lib/pi/agent`, so a person's sign-in in a task leaves no provider key the agent can reach, and
its attended sessions are kept there. Measured there: the agent owns `/home/agent`, so it can rename anything Porelo
or the image puts under it.

## Plan

Read in `SessionIds`, `AgentDefinitionReader` and `TaskSession.fromFiles`.

1. **`SessionIds`** refuses a directory starting with `/` today. It takes one that is absolute too, held to the same
   plain path characters and with no `..`, since it reaches a command run in the container.
2. **`TaskSession.fromFiles`** looks in `"$HOME/<directory>"`; an absolute directory is looked in as it is, quoted
   the same way.
3. **Docs:** the `directory` line in `agents/README.md`.
4. **Red first:** `SessionIdsTest.anAbsoluteSessionDirectoryIsAPlainPathToo` (accepted; `/a/../b` and `/a b` refused),
   then `TaskSessionTest.anAbsoluteSessionDirectoryIsLookedInAsItIs`, which asserts the `find` names
   `/var/lib/pi/agent/sessions`, not `$HOME/...`.

## Acceptance

- A definition with `directory: "/var/lib/pi/agent/sessions/--workspace--"` resumes an attended session from the
  newest file there. Seen to fail first: today the definition is refused.
- A relative directory is still looked for under the home, and `..` stays refused.
- **An older Porelo fails closed:** its `AgentDefinitionReader` refuses an absolute directory, so the whole
  definition does not load and the agent is reported broken, not run with its sessions unfound. Measured in porelo-pi
  on agent API 0.5.0. An agent that uses one therefore requires the first Porelo with this,
  `0.5.1~snapshot.317`, Build 317 of `bf0a07f1`.
