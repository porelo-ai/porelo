@slow
Feature: A daemon start waits for its agent's model credential

    Scenario: a pending stub sign-in stores the credential and the waiting task runs
        Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And a daemon of this scenario's own
        And a stub sign-in is running and waiting to store its credential
        When a daemon start of the stub task waits for that sign-in
        And the stub sign-in is completed and its credential is stored
        Then the waiting stub task is running
