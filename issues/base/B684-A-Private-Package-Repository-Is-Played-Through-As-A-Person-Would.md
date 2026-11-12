# B684 — A Private Package Repository Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B684",
  "title": "A Private Package Repository Is Played Through As A Person Would",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 33,
  "blockedBy": [],
  "covers": "A script plays ST43 at the command line on the test VM as a person would: a task builds against an authenticated Nexus or Artifactory stand-in; the build tool's settings are written at the start and the credential stays in the vault, never inside the task."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed and a stand-in repository with TLS and Basic auth.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST43 (a company's private package repository, with no credential in the task) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a task builds against an authenticated Nexus or Artifactory stand-in; the build tool's settings are written at the start and the credential stays in the vault, never inside the task. Its issues here: [B219](README.md#b219), [B222](README.md#b222), [B467](README.md#b467), [B468](README.md#b468); the ecosystems' EC16 is used through its adapter.

## Plan

- `acceptance/stories/st43.sh`: serve a Maven repository that demands Basic auth over TLS on the VM (a small stand-in serving one jar, its authority added), put its credential with `porelo vault put`, declare it in the project's `tool_repositories` with `credential`, start a task that builds a project depending on the jar; then search the task's files, environment and processes for the credential and find none. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Script (2026-11-13)

`acceptance/stories/st43.sh`, run as the test account on the Ubuntu test VM against the installed porelo:

1. A test authority and a certificate for `127.0.0.1` made with `openssl`; a Maven repository laid out with one jar,
   `org.example.story:story-lib:1.0`, served by a small Python server over TLS that answers only the run's Basic
   credential (401 without it, 405 to a write).
2. The authority appended to the account's `$XDG_CONFIG_HOME/porelo/ca-certificates.pem`, which Porelo's clients on
   the host trust ([credentials](../../doc/credentials.md#an-authority-of-your-own)); the file is put back as it
   was afterwards. No root is needed: the broker reaches the repository from the host, and the task speaks plain
   http to the broker.
3. The credential, a test string made by the run, stored with `porelo vault put repository/st43-<random> --type basic`
   from a file on standard input, in a vault of the run's own.
4. A guarded project declaring it under `tool_repositories` (`ecosystem: maven`, `name: corp`, the stand-in's `url`,
   `credential`), with Maven and a JDK in its image and the `maven` egress set for Maven's own plugins.
5. A task started; read the Maven settings file in the task user's home (a mirror at `http://127.0.0.1:9421/corp/`, no password) and
   `PORELO_REPOSITORY_CORP_URL`; `curl` fetches the jar through the broker byte for byte while sending a credential of
   its own, and a PUT is refused; `mvn package` of a project depending on the jar succeeds, and every request that
   reached the stand-in carried the vault's credential.
6. The credential, as the password, as `user:password` and Base64-encoded, searched in the task's environment, the
   container's own environment, its processes' command lines and environments, its whole filesystem
   (`podman export`), what is mounted into it, the host helpers that name the task and its state directory and logs:
   none expected anywhere, and still in the vault.

The password is never on a command line or printed: it is in owner-only files and reaches `vault put`, the server and
`curl` by standard input or a file, and every output shown passes a filter that replaces it. A trap removes the task,
the project, its image, the vault entry and vault, the server and its files, and the authority.

**Expected failures:** none: [B219](README.md#b219), [B467](README.md#b467) and [B468](README.md#b468) are in Review,
and [B222](README.md#b222) is being closed as covered by [B467](README.md#b467). If the Maven adapter is not installed in the account,
the run says so and falls back to `stub-ecosystem`, and the settings and Maven points fail unexpectedly.

**How it is run** (through the coordinator, on the laptop, from this repository's root):

    scp acceptance/stories/st43.sh <account>@<test-vm>:
    ssh <account>@<test-vm> 'bash -l st43.sh --test-account' 2>&1 | tee st43.log

## Rules for a story test (operator, 2026-11-12)

- A test script is test tooling, not production code: no tests for the script, no red-first, no review loops (one
  Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed on the spot and the run carries on; every fault found, in the script or in the
  product, is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script
  fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: asked before it starts, posted when it ends. No second
  VM, no Molecule beside it; cleaned up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue
  as an expected failure.

## Acceptance

- The script's run on the VM shows every point of the story's test holding, or names each that does not with the
  issue it waits on.
