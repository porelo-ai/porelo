@slow
Feature: A task is started with the values its project declares

    A project declares under 'parameters' the values a task of it is started with. The person starting a
    task hands them in on the command line, from a file or from their shell, and each is checked against
    its declaration before anything is made. A task keeps its values for its whole life, in its
    environment, its record and its agent's guide, and says where each came from.

    Background:
        Given the suite runs as an unprivileged user
        And a project called "valued" of class "guarded" whose project file also says:
            """
            egress:
              sets: []
            parameters:
              type:
                values: [feature, bugfix]
                default: feature
              ticket:
                pattern: "[A-Z]+-[0-9]+"
                required: false
                env: "TASK_TICKET"
              label:
                required: false
            """

    Scenario: nothing comes from the shell unasked, and --set-env takes what is declared
        When a script runs:
            """
            set -e
            export TASK_TICKET=AB-12
            unasked=$(porelo task start unasked --project valued --repository valued --agent stub --dry-run)
            echo "unasked tickets: $(printf '%s\n' "$unasked" | grep -c 'ticket=' || true)"
            porelo task start asked --project valued --repository valued --agent stub --dry-run --set-env
            """
        Then it exits zero
        And its output contains "unasked tickets: 0"
        And its output has a line matching "value +type=feature \(default\)"
        And its output has a line matching "value +ticket=AB-12 \(environment\)"

    Scenario: a value its declaration does not allow makes nothing
        When a script runs:
            """
            set +e
            porelo task start refused --project valued --repository valued --agent stub \
                --attach shell --detach --clearance deny --set type=hotfix
            echo "start exit $?"
            podman container exists porelo-valued-refused && echo "container made" || echo "no container"
            """
        Then its output contains "start exit 64"
        And its output contains "[feature, bugfix]"
        And its output contains "nothing was created"
        And its output contains "no container"

    Scenario: a task holds its values in its environment, its record and its guide, and keeps them
        When a script runs:
            """
            set -e
            export TASK_TICKET=CD-34
            printf 'label: from-a-file\n' > ~/valued-values.yml
            timeout 600 porelo task start kept --project valued --repository valued --agent stub \
                --attach shell --detach --clearance deny \
                --set type=bugfix --set ticket --set-file ~/valued-values.yml
            podman exec porelo-valued-kept sh -c 'echo "env: $PORELO_PARAM_TYPE $TASK_TICKET $PORELO_PARAM_LABEL"'
            podman exec porelo-valued-kept cat /run/porelo/guide/README.md
            porelo task status porelo-valued-kept
            porelo task stop porelo-valued-kept > /dev/null
            set +e
            porelo task start kept --project valued --repository valued --no-input --set type=feature
            echo "again exit $?"
            set -e
            porelo task remove porelo-valued-kept --force > /dev/null
            rm -f ~/valued-values.yml
            """
        Then it exits zero
        And its output contains "env: bugfix CD-34 from-a-file"
        And its output contains "- `type`: `bugfix`, in `$PORELO_PARAM_TYPE`"
        And its output contains "- `ticket`: `CD-34`, in `$TASK_TICKET`"
        And its output has a line matching "values +.*type=bugfix \(command line\).*"
        And its output has a line matching "values +.*ticket=CD-34 \(environment\).*"
        And its output has a line matching "values +.*label=from-a-file \(file .*valued-values.yml\).*"
        And its output contains "stay as they were"
        And its output contains "again exit 64"
