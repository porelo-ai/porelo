@slow
Feature: A task the machine's restart took down comes back whole when it is started

  A reboot stops every container and empties the runtime directory, where a task's sockets, records
  and tokens were. Porelo saves what is knowledge about the task where a reboot does not reach it, and
  keeps its two tokens - the gate's and the provider's stand-in - in the vault, so 'start' brings the
  task back whole: container, helpers, egress and the same tokens its container already holds. The
  shared test machine is never rebooted by the suite, so this reproduces what a reboot leaves: the
  container stopped and its runtime directory gone.

  Background:
    Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "boot" of class "guarded" with a file in it

  Scenario: a restarted task comes back with the tokens its container holds, and says why it was down
    When a task called "kept" is started in "boot" for the "stub" agent and left running
    And a script runs about the task:
      """
      porelo vault list
      test -d "${XDG_STATE_HOME:-$HOME/.local/state}"/porelo/tasks/{task} && echo "what it is was saved at start"
      porelo task stop {task} >/dev/null && rm -rf "$XDG_RUNTIME_DIR"/porelo/{task} && test ! -e "$XDG_RUNTIME_DIR"/porelo/{task} && echo "what a reboot leaves"
      porelo task list
      """
    Then its output contains "what it is was saved at start"
    And its output contains "what a reboot leaves"
    And its output contains "down because the machine restarted"
    # A task's own tokens are not credentials a person put in the vault.
    And its output does not contain "task/"
    # Bringing it back needs its tokens, and a locked vault says so rather than starting half a task.
    When a script runs "porelo vault lock >/dev/null; porelo task start kept --project boot --repository boot --detach"
    Then it exits non-zero
    And its output contains "porelo vault unlock"
    When a script runs "porelo vault unlock --passphrase-command 'printf acceptance' >/dev/null && porelo task start kept --project boot --repository boot --detach"
    Then it exits zero
    And its output contains "restored  what the machine's restart took"
    # The provider's own rejection of the fake key proves the proxy took the token the container holds.
    When the task's container runs:
      """
      porelo-stub-cli
      """
    Then its output contains "authentication_error"
    And its output does not contain "porelo:"
    # And the gate took the token the container's git holds.
    When a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "after the restart" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"' \
          && echo "the push went through after the restart"
      """
    Then its output contains "the push went through after the restart"
    # Removing it forgets what was saved for it.
    When a script runs about the task:
      """
      porelo task remove {task} --force >/dev/null
      # {task} arrives quoted, so it stays outside the double quotes.
      test ! -e "${XDG_STATE_HOME:-$HOME/.local/state}"/porelo/tasks/{task} && echo "nothing of it is kept"
      """
    Then its output contains "nothing of it is kept"
