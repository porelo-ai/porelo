@upgrade @isolated
Feature: A kept task made by the predecessor survives a package upgrade

    Run this feature alone in a disposable account with pinned, pre-staged packages. It replaces
    that account's CLI, daemon and hooks, never a machine's packages. It restarts only its own daemon.
    The baseline must create the old name itself; a renamed candidate container proves nothing.
    This is deliberately outside @slow: selecting not @slow must not silently lose the upgrade proof.

    Scenario: old task state stays with its original container through upgrade, daemon restart and resume
        Given the pinned predecessor is installed in the isolated upgrade account
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "upgrade-route" of kind "api-key"
        And a project called "porelo" of class "guarded" whose project file also says:
            """
            credentials:
              upgrade-route: upgrade-route
            mail:
              peers:
                reviewer:
                  address: "upgrade:waiting"
                  mode: allow
            mcp:
              methods: ["List", "Start", "Stop"]
              agents:
                stub:
              tasks: 2
              minutes: 1
            """
        And a project called "upgrade" of class "guarded" with a file in it
        And a daemon of this scenario's own
        When the predecessor starts the kept upgrade task
        And the predecessor attaches to the kept upgrade task and detaches
        Then the "stub" agent in container "porelo-porelo-kept" reaches work without being asked anything
        When the old task keeps its upgrade witnesses
        And the candidate replaces the predecessor while the old task runs
        Then the old upgrade task still owns its running container and helpers
        And the old upgrade task can be attached to
        And the old upgrade task's watches and MCP ownership return
        When the daemon's unit is stopped
        And the daemon is started again
        Then the old upgrade task still owns its running container and helpers
        And the old upgrade task's watches and MCP ownership return
        When the old upgrade task is stopped and resumed by logical name
        Then the resumed upgrade task retains its witnesses
        And the old upgrade task can be attached to
        When new upgrade tasks are started with the candidate's names
        Then only the upgrade scenario's tasks are removed
