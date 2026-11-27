@slow
Feature: What a task holds is what its start names, through its own endpoint, at its gate and in its messages

    A start names the parts of a task: its workspace, the project's services, the account it writes as and its
    rights. The rights decide what the task's own MCP endpoint carries beyond reading, which waiting push it reads,
    and whether it pushes; a task started through an endpoint holds at most what its parent holds; a task that reads
    another task's work sends nothing but to its review. Each task here runs the stub agent; what it does through its
    endpoint it does from inside its container, with the token its guide names. A project whose file has an `mcp`
    section gives its tasks an endpoint only through the daemon, so its tasks are started there.

    # What a task does through its own endpoint is decided per task: it carries Start, Stop, Done and Report only
    # where the task's recorded rights hold start, stop, done and report. A method the task's rights lack answers as
    # one the endpoint does not have.
    Scenario: a task holding report and not start reports its result and cannot start a task through its endpoint
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "parted" of class "guarded" whose project file also says:
            """
            limits:
              reports: 1
            mcp:
              methods: ["List", "Pending", "Review", "Start", "Report"]
              agents:
                stub:
              tasks: 2
              minutes: 5
            """
        And a daemon of this scenario's own
        And the scenario's vault is open to its daemon
        When a task called "reporter" is started in "parted" through the daemon for the stub agent with the rights "report" and left running
        Then the daemon lists the task "reporter" of "parted" with these parts:
            | workspace | own    |
            | rights    | report |
        And the own endpoint of the task "reporter" offers "List, Pending, Review, Report" and not "Start"
        # Report takes the socket's own task, which its container names: "{container}" stands for it.
        When the task "reporter" calls "Report" on its own endpoint with:
            """
            {"task": "{container}", "kind": "status", "text": "The stub reported its result."}
            """
        Then its output has a line matching '.*"seq" *: *1\b.*'
        When a script runs "porelo task reports {task}" about the task
        Then it exits zero
        And its output contains "The stub reported its result."
        When the task "reporter" calls "Start" on its own endpoint with:
            """
            {"project": "parted", "repository": "parted", "task": "unstarted", "agent": "stub",
             "prompt": "never run", "minutes": 1}
            """
        Then its output mentions one of "No tool 'Start', MethodNotFound"
        When a script runs "porelo task list --project parted"
        Then its output does not contain "unstarted"

    # A task started through a task's endpoint gets at most the workspace, equipment and rights of the task that
    # starts it; a start asking more is refused StartRuleBroken with the rule exceedsParent, naming what exceeds.
    Scenario: a task holding start starts a child with fewer rights and is refused one with more
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "parted" of class "guarded" whose project file also says:
            """
            limits:
              reports: 1
            mcp:
              methods: ["List", "Pending", "Review", "Start", "Report"]
              agents:
                stub:
              tasks: 2
              minutes: 5
            """
        And a daemon of this scenario's own
        And the scenario's vault is open to its daemon
        When a task called "starter" is started in "parted" through the daemon for the stub agent with the rights "push, reply, start" and left running
        Then the own endpoint of the task "starter" offers "List, Pending, Review, Start" and not "Report"
        When the task "starter" calls "Start" on its own endpoint with:
            """
            {"project": "parted", "repository": "parted", "task": "fewer", "agent": "stub",
             "prompt": "a child holding less than its parent", "minutes": 1, "rights": ["reply"]}
            """
        Then its output has a line matching '.*"exitCode" *: *0\b.*'
        And the daemon lists the task "fewer" of "parted" with these parts:
            | workspace       | own    |
            | rights          | reply  |
            | projectServices | true   |
            | writeAs         | author |
        When the task "starter" calls "Start" on its own endpoint with:
            """
            {"project": "parted", "repository": "parted", "task": "wider", "agent": "stub",
             "prompt": "a child holding more than its parent", "minutes": 1,
             "rights": ["push", "reply", "start", "stop"]}
            """
        Then its output contains "StartRuleBroken"
        And its output contains "exceedsParent"
        And its output contains "rights stop"
        When a script runs "porelo task list --project parted"
        Then its output does not contain "wider"

    # Pending, Review and ReviewPage answer a task only for the push that is its place: the pushing task for its
    # own. Every other waiting push of the project is absent from Pending and refused by the other two as no such
    # push.
    Scenario: a task at its own push reads its own waiting push through Pending and not another task's
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "parted" of class "guarded" whose project file also says:
            """
            mcp:
              methods: ["List", "Pending", "Review"]
              agents:
                stub:
              tasks: 2
              minutes: 5
            """
        And a daemon of this scenario's own
        And the scenario's vault is open to its daemon
        When a task called "author" is started in "parted" through the daemon for the stub agent and left running
        And a task called "bystander" is started in "parted" through the daemon for the stub agent and left running
        And the task "author" pushes a change to its gate
        And the task "bystander" pushes a change to its gate
        # Both wait at the gate, as a person sees it.
        When a script runs "porelo gate pending --project parted"
        Then its output has a line matching "author +\S+ +[0-9a-f]+ +acceptance: the change of author"
        And its output has a line matching "bystander +\S+ +[0-9a-f]+ +acceptance: the change of bystander"
        When the task "author" calls "Pending" on its own endpoint with:
            """
            {"project": "parted"}
            """
        Then its output has a line matching '.*"name" *: *"author".*'
        And its output contains "acceptance: the change of author"
        And its output does not contain "bystander"
        When the task "author" calls "Review" on its own endpoint with:
            """
            {"project": "parted", "name": "author"}
            """
        Then its output contains "acceptance: the change of author"
        When the task "author" calls "Review" on its own endpoint with:
            """
            {"project": "parted", "name": "bystander"}
            """
        Then its output contains "NoSuchPush"
        And its output does not contain "acceptance: the change of bystander"

    # The task row in List and Watch always carries the same four members, each written out (a start naming none as
    # the default set). A start naming no part gets own work, the project's services, push, reply and the account
    # author. readOnly, allRepositories and noGate stay as the choice of the workspace.
    Scenario: a start naming no part shows the default set, and one without a gate shows no workspace and no right
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "partless" of class "guarded" with a file in it
        And a daemon of this scenario's own
        When a task called "plain" is started in "partless" for the stub agent naming no part and left running
        Then the daemon lists the task "plain" of "partless" with these parts:
            | workspace       | own         |
            | rights          | push, reply |
            | projectServices | true        |
            | writeAs         | author      |
            | readLanded      | []          |
        When a task called "ungated" is started in "partless" for the stub agent with the options "--no-gate" and left running
        Then the daemon lists the task "ungated" of "partless" with these parts:
            | workspace | none |
            | rights    |      |

    # --role and --reads are gone. A start that breaks one of Porelo's own start rules is refused; the command line
    # exits 64 with "porelo: StartRuleBroken: <rule> (<detail>)".
    Scenario Outline: a start by hand refuses the options that are gone and a push without its own work
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "partless" of class "guarded" with a file in it
        When a script runs 'porelo task start refused --project partless --repository partless --agent stub --detach --clearance deny <options>; echo "exit $?"'
        Then its output contains "<said>"
        And its output contains "exit <exit>"
        When a script runs "podman ps -a --format '{{.Names}}'"
        Then its output does not contain "partless_refused"

        Examples:
            | options                     | said                                                | exit |
            | --role reviewer             | '--role' is not an option of 'porelo task start'    | 64   |
            | --reads author              | '--reads' is not an option of 'porelo task start'   | 64   |
            | --right push --read-only    | porelo: StartRuleBroken: pushWithoutOwnWork         | 64   |

    # `porelo task start --right <name>` (repeatable; any `--right` replaces the default rights).
    Scenario: a task at its own work started with reply alone has its push refused at its gate
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "partless" of class "guarded" with a file in it
        And a daemon of this scenario's own
        When a task called "replier" is started in "partless" for the stub agent with the options "--right reply" and left running
        Then the daemon lists the task "replier" of "partless" with these parts:
            | workspace | own   |
            | rights    | reply |
        And a push from the task is refused
        When a script runs "porelo gate pending --project partless"
        Then its output does not contain "replier"

    # A message such a task hands to any recipient but its own review is refused before it reaches a transport's
    # queue, a moderation or a budget, and the task is told why, naming the rule. A task at its own work is enrolled
    # and sends as today.
    Scenario: a task that reads another task's waiting push sends nothing to a peer, while a task at its own work does
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a message transport "parked" that keeps every message waiting is installed
        And a project called "sender" of class "guarded" whose project file also says:
            """
            mail:
              peers:
                outside:
                  address: "parked:waiting"
                  mode: allow
            """
        And a daemon of this scenario's own
        And the scenario's vault is open to its daemon
        # Certified, so the daemon signs what it sends: an unsigned message is held for a person.
        When a certified task is started in "sender" for the stub agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'cd /workspace && echo sent >> README.md && git add README.md && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work another task reads" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
            """
        Then it exits zero
        When the task's agent hands its mailbox a message to "outside" saying "own work goes out"
        And a script runs "porelo talk pass {task}" about the task
        Then within 60 seconds this script about the task exits zero:
            """
            grep -qs '"event":"queued".*"peer":"outside","detail":"parked"' "${XDG_STATE_HOME:-$HOME/.local/state}/porelo/mail/"{task}"/record/log.jsonl"
            """
        When the task "reader" is started in "sender" at the waiting push of "sender" for the stub agent with the rights "reply" and left running
        And the task's agent hands its mailbox a message to "outside" saying "reading work stays here"
        And a script runs "porelo talk pass {task}" about the task
        Then within 60 seconds this script about the task exits zero:
            """
            podman exec --user agent {task} sh -c 'cat /run/porelo/mail/inbox/new/* /run/porelo/mail/inbox/cur/* 2>/dev/null' | grep -q readsAnothersWork
            """
        When a script runs about the task:
            """
            q="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/mail/"{task}"/queue"
            if grep -rqs 'reading work stays here' "$q"; then echo "the reader's message is queued"; fi
            if grep -qs '"event":"queued"' "${q%/queue}/record/log.jsonl"; then echo "the reader's message is queued"; fi
            find "$q" -type f 2>/dev/null | sed 's/^/queued: /'
            """
        Then its output does not contain "the reader's message is queued"
        And its output does not contain "queued: "
        When a script runs:
            """
            for t in $(podman ps -a --format '{{.Names}}' | grep '^porelo-sender_'); do porelo task remove "$t" --force > /dev/null 2>&1; done
            places="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/organization-projects"
            rm -f "$places/sender.json" "$places/sender.git"
            true
            """
