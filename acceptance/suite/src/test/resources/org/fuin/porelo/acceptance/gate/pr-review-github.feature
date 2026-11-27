@slow @github
Feature: A task's runs at a pull request on GitHub, each answer released before it goes out

    The stub forge's two-run scenario, once against GitHub itself: the forge is the installed github forge
    adapter, the pull request is in a scratch repository the scenario makes in a test organization and removes
    at its end, and the test identity's token is the vault entry git.token.github.com, which never reaches the
    task. The test's own calls to GitHub, the answers in the thread among them, go from the host the suite runs
    on, with the same identity. Selected only by its tag; it fails, never skips, when its variables are missing.

    Scenario: a reviewer comments on GitHub, and answers the test identity twice, each answer released before it goes out
        Given the GitHub test identity is given by "PORELO_GITHUB_TEST_TOKEN" and "PORELO_GITHUB_TEST_ORG"
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And the vault holds the value of "PORELO_GITHUB_TEST_TOKEN" as "git.token.github.com" of kind "api-key"
        And the installed forge adapter "github" reads "github.com"
        And a scratch repository in the test organization has a pull request on GitHub
        And in its next run the stub agent submits "submitted-comment.sarif.json" at the pull request on GitHub
        When the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on GitHub
        Then it exits zero
        And the task "reviewer" holds 1 outgoing item
        And the pull request on GitHub has no review comment
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on GitHub reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
        # The next run is handed what the test identity answered there, and answers in the same thread.
        When the test identity answers the task's thread on GitHub: "Why refuse it there and not in the form?"
        And in its next run the stub agent replies to every open thread it is given at the pull request on GitHub
        And the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on GitHub
        Then it exits zero
        And the task was given "Why refuse it there and not in the form?"
        And the task "reviewer" holds 1 outgoing item
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on GitHub reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
            | Why refuse it there and not in the form?                           |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
        When the test identity answers the task's thread on GitHub: "And a blank user name?"
        And in its next run the stub agent replies to every open thread it is given at the pull request on GitHub
        And the task "reviewer" is run by the stub agent as review with the rights "reply, openThreads" at the pull request on GitHub
        Then it exits zero
        And the task was given "And a blank user name?"
        When the owner account releases what the task "reviewer" holds
        Then the task's thread on GitHub reads, in order:
            | A blank password reaches hash() here; refuse it before the lookup. |
            | Why refuse it there and not in the form?                           |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
            | And a blank user name?                                             |
            | The check now runs first; LoginTest.expiredTokenIsDenied shows it.  |
        And every comment of the task's thread on GitHub is the test identity's
        And the task's container environment does not contain the value of "PORELO_GITHUB_TEST_TOKEN"
        And no file in the task's container holds the value of "PORELO_GITHUB_TEST_TOKEN"
        And no log the task left contains the value of "PORELO_GITHUB_TEST_TOKEN"
        And a push from the task is refused
        And a script runs "porelo task remove {task} --force" about the task
