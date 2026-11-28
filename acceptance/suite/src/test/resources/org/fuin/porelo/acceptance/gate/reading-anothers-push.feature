@slow
Feature: A task reads another task's push where it waits at the gate

    A task started at another task's waiting push of its own project reads that push alone, pinned at its
    head, and writes into the push's own threads at the gate as "task NAME": no account, no forge, nothing
    leaves the machine. What the reading task sends is the stub agent's: the pushed work carries the stub's
    review file, which the reading task's workspace holds, and the stub sends it to the review in its one run.
    The reading task has no certified key, as on a plain machine: at a waiting push nothing it sends is held,
    signed or not.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a daemon of this scenario's own

    # Its gate shows that one push, pinned at its head and base, read only, and no other waiting work, not that of other tasks of the same project either.
    Scenario: a task started at another task's waiting push reads that push alone and comments on it as itself
        Given a project called "readpush" of class "guarded" with a file in it
        When a task called "author" is started in "readpush" for the "stub" agent and left running
        And the task "author" in "readpush" pushes work whose stub review comments
        And a task called "other" is started in "readpush" for the "stub" agent and left running
        And the task "other" in "readpush" pushes work whose stub review comments
        And a script runs "porelo gate pending --project readpush"
        Then it exits zero
        And its output has a line matching "author .*"
        And its output has a line matching "other .*"
        When the task "reader" is started in "readpush" at the waiting push of "author" for the stub agent with the rights "reply, openThreads"
        Then it exits zero
        And the task's workspace stands on the commit "author" pushed
        And the task cannot fetch the work "other" pushed
        # Two lines: the reader's clone pushes to no address, and its gate refuses a push sent to it straight.
        And a push from the task through its clone is refused, naming "porelo-reviewer-pushes-nothing"
        And a push from the task straight to its gate is refused
        When a script runs "porelo task logs {task} gate.log" about the task
        Then its output has a line matching ".*refused: .*push.*"
        # The run's conversation is the local threads of that push; the task's items reach those threads as `Task(id)`, shown as "task NAME".
        Then within 120 seconds this script exits zero:
            """
            porelo gate comments author --project readpush | grep -q '^  task porelo-readpush_reader  notes.txt'
            """
        When a script runs "porelo gate comments author --project readpush"
        Then it exits zero
        And its output has a line matching "task porelo-readpush_reader  notes.txt  new 3-3"
        And its output contains "Why does three follow two here?"
        When a script runs "porelo gate held {task}" about the task
        Then it exits zero
        And its output contains "nothing held"

    Scenario: a reviewer continues the developer's conversation on a waiting push
        Given a project called "conversedpush" of class "offline" with a file in it
        And the scenario's vault is open to its daemon
        # A text reply from the developer's mailbox needs its task's certified key. The reviewer needs none.
        When a certified task is started in "conversedpush" for the stub agent and left running
        And the task "conversedpush" in "conversedpush" pushes work whose stub review comments
        And the task "reviewer" is started in "conversedpush" at the waiting push of "conversedpush" for the stub agent with the rights "reply, openThreads"
        Then it exits zero
        And within 120 seconds this script exits zero:
            """
            porelo gate comments conversedpush --project conversedpush | grep -q '^  task porelo-conversedpush_reviewer  notes.txt  new 3-3$'
            """
        When a script runs "porelo gate comments conversedpush --project conversedpush"
        Then it exits zero
        And its output contains "Why does three follow two here?"
        When a script runs "porelo gate held {task}" about the task
        Then it exits zero
        And its output contains "nothing held"
        When the task "conversedpush" answers its review thread saying "Three follows two because the file lists the numbers in order."
        Then within 120 seconds this script exits zero:
            """
            porelo gate comments conversedpush --project conversedpush | grep -q 'Three follows two because the file lists the numbers in order.'
            """
        When a script runs "porelo gate comments conversedpush --project conversedpush"
        Then it exits zero
        And its output has a line matching "task conversedpush  replies"
        When in its next run the stub agent replies to every open thread it is given
        And the task "reviewer" is run in "conversedpush" at the waiting push of "conversedpush" for the stub agent with the rights "reply, openThreads"
        Then it exits zero
        And the task was given the whole conversation
        And the task was given "Why does three follow two here?"
        And the task was given "Three follows two because the file lists the numbers in order."
        And within 120 seconds this script exits zero:
            """
            porelo gate comments conversedpush --project conversedpush | grep -q '^  task porelo-conversedpush_reviewer  replies$'
            """
        When a script runs "porelo gate comments conversedpush --project conversedpush"
        Then it exits zero
        And its output contains "Two stays; it is the middle line."
        And its output has a line matching "thread [0-9a-f-]+  open"
        And the one thread of the push of "conversedpush" reads, in order:
            | porelo-conversedpush_reviewer | Why does three follow two here?                                |
            | conversedpush                 | Three follows two because the file lists the numbers in order. |
            | porelo-conversedpush_reviewer | Two stays; it is the middle line.                              |
        When a script runs "porelo gate held {task}" about the task
        Then it exits zero
        And its output contains "nothing held"
        When a script runs "porelo gate held porelo-conversedpush_conversedpush"
        Then it exits zero
        And its output contains "nothing held"
        And the stub forge's write log is empty
        When a script runs "porelo gate pending --project conversedpush"
        Then it exits zero
        And its output has a line matching "conversedpush .*"

    # A task whose workspace is another task's work takes another task's waiting push of its own project as its place, through the existing `task start --place push:<name>`; never another project's push.
    Scenario: a start at another task's waiting push is refused before anything is made when it names no right, names push, or is in another project
        Given a project called "readrules" of class "guarded" with a file in it
        And a project called "readelse" of class "guarded" with a file in it
        When a task called "author" is started in "readrules" for the "stub" agent and left running
        And the task "author" in "readrules" pushes work whose stub review comments
        # Porelo's rule for a task reading another task's work holds here as at a pull request: no project services, no `push`, no added credentials, clearance never `allow` or `off`.
        When the task "unnamed" is started in "readrules" at the waiting push of "author" for the stub agent naming no right
        Then it exits non-zero
        And its output contains "StartRuleBroken: foreignWorkNamesNoRights"
        And its output contains "nothing was created"
        When the task "leaking" is started in "readrules" at the waiting push of "author" for the stub agent with the rights "push"
        Then it exits non-zero
        And its output contains "StartRuleBroken: foreignWorkLeadsOut"
        And its output contains "nothing was created"
        When the task "stranger" is started in "readelse" at the waiting push of "author" for the stub agent with the rights "reply"
        Then it exits non-zero
        And its output contains "NoSuchPush"
        When a script runs "porelo task list; podman ps -a --format '{{.Names}}'"
        Then its output does not contain "readrules_unnamed"
        And its output does not contain "readrules_leaking"
        And its output does not contain "readelse_stranger"

    # The run's conversation is the local threads of that push; the task's items reach those threads as `Task(id)`, shown as "task NAME". No account is used and nothing leaves the machine.
    Scenario: a reply at a waiting push goes into the owner account's thread at once, with nothing held, in a guarded project too
        Given a project called "readreply" of class "guarded" with a file in it
        When a task called "author" is started in "readreply" for the "stub" agent and left running
        And the task "author" in "readreply" pushes work whose stub review replies
        And the owner account comments on the work of "author": "Why two?"
        And the task "reader" is started in "readreply" at the waiting push of "author" for the stub agent with the rights "reply"
        Then it exits zero
        Then within 120 seconds this script exits zero:
            """
            porelo gate comments author --project readreply | grep -q '^  task porelo-readreply_reader  replies'
            """
        When a script runs "porelo gate comments author --project readreply"
        Then it exits zero
        And its output has a line matching "owner account  notes.txt  new 2-2"
        And its output has a line matching "task porelo-readreply_reader  replies"
        And its output contains "Two stays; it is the middle line."
        When a script runs "porelo gate held {task}" about the task
        Then it exits zero
        And its output contains "nothing held"

    # A task holding `resolveThreads` resolves a thread at its place.
    Scenario: a task holding resolveThreads resolves a thread of the push it reads, and one without it is refused
        Given a project called "readresolve" of class "guarded" with a file in it
        When a task called "author" is started in "readresolve" for the "stub" agent and left running
        And the task "author" in "readresolve" pushes work whose stub review resolves
        And the owner account comments on the work of "author": "Why two?"
        And the task "unresolving" is started in "readresolve" at the waiting push of "author" for the stub agent with the rights "reply"
        Then it exits zero
        And within 120 seconds the task "unresolving" is told "RightMissing: resolveThreads"
        When a script runs "porelo gate comments author --project readresolve"
        Then it exits zero
        And its output has a line matching "thread [0-9a-f-]+  open"
        When the task "resolving" is started in "readresolve" at the waiting push of "author" for the stub agent with the rights "reply, resolveThreads"
        Then it exits zero
        Then within 120 seconds this script exits zero:
            """
            porelo gate comments author --project readresolve | grep -q '  resolved by task porelo-readresolve_resolving$'
            """
        When a script runs "porelo gate comments author --project readresolve"
        Then it exits zero
        And its output has a line matching "thread [0-9a-f-]+  resolved by task porelo-readresolve_resolving"
        When a script runs "porelo gate held {task}" about the task
        Then it exits zero
        And its output contains "nothing held"

    # A task holding `reject` discards the waiting push it reads.
    Scenario: a task holding reject discards the waiting push it reads, and one without it leaves the push waiting
        Given a project called "readreject" of class "guarded" with a file in it
        When a task called "discarded" is started in "readreject" for the "stub" agent and left running
        And the task "discarded" in "readreject" pushes work whose stub review rejects
        And the task "unrejecting" is started in "readreject" at the waiting push of "discarded" for the stub agent with the rights "reply"
        Then it exits zero
        And within 120 seconds the task "unrejecting" is told "RightMissing: reject"
        When a script runs "porelo gate pending --project readreject"
        Then it exits zero
        And its output has a line matching "discarded .*"
        When the task "rejecting" is started in "readreject" at the waiting push of "discarded" for the stub agent with the rights "reject"
        Then it exits zero
        Then within 120 seconds this script exits zero:
            """
            pending=$(porelo gate pending --project readreject) && ! printf '%s\n' "$pending" | grep -q '^discarded '
            """
        And within 120 seconds the task "discarded" is told "This cannot be reviewed into shape."
