@slow @forgejo
Feature: A task's runs at a pull request on Forgejo, each answer released before it goes out

    The stub forge's two-run scenario, once against Forgejo itself: the forge is the installed forgejo forge
    adapter, the pull request is in a scratch repository the scenario makes under the test owner and removes
    at its end, and the test identity's token is the vault entry git.token.<the instance host>, which never reaches the
    task. The test's own calls to Forgejo, the answers in the thread among them, go from the host the suite runs
    on, with the same identity. Forgejo has no reply in a thread, no comment on several lines and no suggestion: every
    word there is a single-line comment, and a thread is the comments on one path and line. Selected only by its tag;
    it fails, never skips, when its variables are missing.

    Scenario: a reviewer comments on Forgejo, and answers the test identity twice, each answer released before it goes out
        Given the Forgejo test identity is given by its environment
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And the Forgejo test token is the credential of the test instance
        And a daemon of this scenario's own
        And the scenario's vault is open to its daemon
        And the installed forge adapter "forgejo" reads the Forgejo test instance
        And the forge adapter "forgejo" trusts the test instance's certificate
        And a scratch repository under the test owner has a pull request on Forgejo
        And a guarded project called "forgejoreview" whose upstream is the scratch repository on Forgejo
        And in its next run the stub agent comments on its line at the pull request on Forgejo: "A blank password reaches hash() here; refuse it before the lookup."
        When the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on Forgejo
        Then it exits zero
        And the task "reviewer" holds 1 outgoing item
        And the pull request on Forgejo has no review comment
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on Forgejo reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
        # The next run is handed what the test identity answered there, and answers on the same line: Forgejo has no reply in a thread.
        When the test identity answers the task's thread on Forgejo: "Why refuse it there and not in the form?"
        And in its next run the stub agent comments on its line at the pull request on Forgejo: "The check now runs first; LoginTest.expiredTokenIsDenied shows it."
        And the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on Forgejo
        Then it exits zero
        And the task was given "Why refuse it there and not in the form?"
        And the task "reviewer" holds 1 outgoing item
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on Forgejo reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
            | Why refuse it there and not in the form?                           |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
        When the test identity answers the task's thread on Forgejo: "And a blank user name?"
        And in its next run the stub agent comments on its line at the pull request on Forgejo: "The check now runs first; LoginTest.expiredTokenIsDenied shows it."
        And the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on Forgejo
        Then it exits zero
        And the task was given "And a blank user name?"
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on Forgejo reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
            | Why refuse it there and not in the form?                           |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
            | And a blank user name?                                             |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
        And every comment of the task's thread on Forgejo is the test identity's
        And the task's container environment does not contain the value of "PORELO_FORGEJO_TEST_TOKEN"
        And no file in the task's container holds the value of "PORELO_FORGEJO_TEST_TOKEN"
        And no log the task left contains the value of "PORELO_FORGEJO_TEST_TOKEN"
        And a push from the task is refused
        And a script runs "porelo task remove {task} --force" about the task
