# B751 — Small Faults Found Playing Stories On A Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "B751",
  "title": "Small Faults Found Playing Stories On A Test VM",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 76,
  "blockedBy": [],
  "covers": "A forge's refusal reaches the agent without Porelo's internal git command line, an upstream that cannot be reached is said with one full stop, and an unknown option of `task start` answers 64 as other commands do.",
  "openQuestions": 0
}
```

**Why this size.** S: three small changes in three modules, one test each. Estimate: 45 minutes (Core's median for
S).

**What must be true.** What the agent and the person read is the forge's or Porelo's own sentence, and wrong use
answers one code everywhere.

Found on a test VM while playing [B681](README.md#b681) and [B682](README.md#b682) (2026-11-13):

1. **A forge's refusal carries Porelo's git command line.** A push the forge refused (a `pre-receive` hook) reached
   the agent, and `gate.log`, as:

   ```
   remote: the upstream refused it: 'git -c credential.helper= -c gpg.ssh.program=ssh-keygen -c credential.helper= --git-dir $XDG_DATA_HOME/porelo/mirrors/st39-….git push --force --end-of-options http://127.0.0.1:…/st39.git 6463…:refs/heads/porelo/deliver' failed with exit code 1: remote: st39: this forge takes nothing today   To http://…  ! [remote rejected] … (pre-receive hook declined) error: failed to push some refs to …
   ```

   The forge's words are there, but they come after the host's mirror path and Porelo's own options, all on one
   line. `GitGate.passOn` (`gate`) returns `"the upstream refused it: " + lease.failure(ex).getMessage()`, and the
   `CommandException`'s message names the command. Say "the forge refused it:" followed by the forge's `remote:` lines
   and its `! [remote rejected]` reason, without the command.
2. **Two full stops.** A start on an upstream that nothing answers said `porelo: 127.0.0.1 could not be reached from
   here. Check the host address, DNS and network connection before retrying; changing a credential cannot repair the
   connection..`. `StartCheck.said` (`apps/app-task`) appends `"."` to `UpstreamProbe`'s detail (`apps/app-model`),
   which already ends with one. Append it only when the sentence does not already end with one.
3. **An unknown option of `task start` answers 2.** `porelo task start x --insecure --agent stub --no-input` said
   `porelo: '--insecure' is not an option of 'porelo task start'` and exited 2, picocli's default. Many commands set
   `exitCodeOnInvalidInput = 64`, and `doc/commands.md` documents 64 as invalid options. `TaskRunCommand`
   (`apps/app-task`) sets none. Set 64 on the root command so every subcommand inherits it, or on each command that
   lacks it.
4. **The legs' deploy refuses a build's own run stamp.** A native build made with
   `-Dporelo.snapshot.run=20261114T044528` names its package `~snapshot.20261114T044528+local...`, and
   `deploy --skip-build --run 20261114T044528` then stopped with `porelo: For input string: "20261114T044528"`.
   `Deploy.RUN` (`acceptance/legs`) reads only the digits before the `T`, and `compareDotted` parses the whole `--run`
   value as dotted numbers. Read a run the way the build writes it, so the stamp a package was built with is accepted
   for it.

First red, in each module's tests: `GitGateTest` refuses a push with a hook and expects the hook's words without
`--git-dir`; `StartCheckTest` expects no `..`; `CliErrorsTest` or `TaskRunCommandTest` expects 64 for an unknown
option; `DeployTest` deploys a package named with a stamp, given that stamp as `--run`.

## Acceptance

- A refusal by the forge reaches the agent and `gate.log` with the forge's own words and no Porelo command line.
- No refusal of a start ends with two full stops.
- `porelo task start` with an unknown option answers 64, as the other commands do.
- The legs' `deploy --skip-build --run <stamp>` installs a package built with that stamp.
