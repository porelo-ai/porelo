@slow
Feature: A person asks what a branch or a commit built

    The query uses the project's build reader and vault credential on the host without starting a task.
    The stub forge reads answers.json beside its executable, as in task-builds.feature.

    Background:
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "forge-token" of kind "api-key"
        And an online project called "build-status" pushing to a repository of its own, whose project file also says:
            """
            builds:
              forge: "stub-forge"
              credential: "forge-token"
            """
        When a script runs:
            """
            test -x ~/.local/share/porelo/builds/stub-forge
            """
        Then it exits zero

    Scenario Outline: a branch names its commit, verdict, jobs and requested log
        When a script runs:
            """
            cat > ~/.local/share/porelo/builds/answers.json <<'JSON'
            {"heads":{"main":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"builds":{"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":{"verdict":"<verdict>","jobs":[{"name":"Build / unit tests","result":"<verdict>","log":"MARKER-unit-log\n"},{"name":"Build / lint","result":"success","log":"MARKER-lint-log\n"}]}}}
            JSON
            porelo build status -p build-status --branch=main --logs=all
            code=$?
            echo "build exit: $code"
            test "$code" -eq <code>
            """
        Then it exits zero
        And its output contains "build exit: <code>"
        And its output contains "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        And its output contains "verdict: <verdict>"
        And its output contains "Build / unit tests"
        And its output contains "Build / lint"
        And its output contains "MARKER-unit-log"
        And its output contains "MARKER-lint-log"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

        Examples:
            | verdict | code |
            | failure | 1    |
            | success | 0    |

    Scenario: a full commit returns the same build as JSON with a failed job's log by default
        When a script runs:
            """
            cat > ~/.local/share/porelo/builds/answers.json <<'JSON'
            {"heads":{},"builds":{"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":{"verdict":"failure","detail":"tests failed","jobs":[{"name":"unit tests","result":"failure","log":"MARKER-failure-log\n"},{"name":"lint","result":"success","log":"MARKER-success-log\n"}]}}}
            JSON
            porelo build status -p build-status -r build-status --commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --json
            code=$?
            echo "build exit: $code"
            test "$code" -eq 1
            """
        Then it exits zero
        And its output contains "build exit: 1"
        And its output contains "\"project\":\"build-status\""
        And its output contains "\"repository\":\"build-status\""
        And its output contains "\"branch\":null"
        And its output contains "\"commit\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
        And its output contains "\"verdict\":\"failure\""
        And its output contains "\"detail\":\"tests failed\""
        And its output contains "MARKER-failure-log"
        And its output does not contain "MARKER-success-log"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

    Scenario: no logs still returns the job results
        When a script runs:
            """
            cat > ~/.local/share/porelo/builds/answers.json <<'JSON'
            {"heads":{"main":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"builds":{"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":{"verdict":"failure","jobs":[{"name":"unit tests","result":"failure","log":"MARKER-omitted-log\n"}]}}}
            JSON
            porelo build status -p build-status --branch=main --logs=none
            code=$?
            echo "build exit: $code"
            test "$code" -eq 1
            """
        Then it exits zero
        And its output contains "build exit: 1"
        And its output contains "unit tests - failure"
        And its output does not contain "MARKER-omitted-log"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

    Scenario Outline: a project without builds names the configuration it needs
        Given a project called "no-builds" with a file in it
        When a script runs:
            """
            porelo build status -p <project> --branch=main
            code=$?
            echo "build exit: $code"
            test "$code" -eq 78
            """
        Then it exits zero
        And its output contains "build exit: 78"
        And its output contains "no builds:"
        And its output contains "security_class: online"
        And its output contains "doc/project-file.md"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

        Examples:
            | project   |
            | no-builds |
            | default   |

    Scenario: a missing credential entry names the entry to store
        Given an online project called "missing-entry" pushing to a repository of its own, whose project file also says:
            """
            builds:
              forge: "stub-forge"
              credential: "missing-forge-token"
            """
        When a script runs:
            """
            porelo build status -p missing-entry --branch=main
            code=$?
            echo "build exit: $code"
            test "$code" -eq 77
            """
        Then it exits zero
        And its output contains "build exit: 77"
        And its output contains "porelo vault put missing-forge-token"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

    Scenario: a locked vault names unlock before asking the forge
        When a script runs:
            """
            porelo vault lock
            porelo build status -p build-status --branch=main
            code=$?
            echo "build exit: $code"
            test "$code" -eq 77
            """
        Then it exits zero
        And its output contains "build exit: 77"
        And its output contains "porelo vault unlock"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"

    Scenario: a refusal from the forge names the retry time
        When a script runs:
            """
            echo '{"refuse":{"reason":"RATE_LIMITED","detail":"forge quota used","retryAfter":37}}' > ~/.local/share/porelo/builds/answers.json
            porelo build status -p build-status --branch=main
            code=$?
            echo "build exit: $code"
            test "$code" -eq 69
            """
        Then it exits zero
        And its output contains "build exit: 69"
        And its output contains "RateLimited"
        And its output contains "retry after 37 seconds"
        And its output does not contain the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL"
