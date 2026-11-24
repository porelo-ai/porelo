@slow
Feature: Review comments on waiting work, carried back to the task that pushed it

    The owner account comments on lines of a push that waits at the gate, and a reject carries the
    threads still open back to the task as untrusted data. Every comment is checked against the
    commit it is written on.

    Scenario: the owner account comments and resolves, and a reject carries the open threads back
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "commented" of class "guarded" with a file in it
        When a task called "answers" is started in "commented" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman exec --user agent {task} sh -c 'cd /workspace && printf "one\ntwo\nthree\n" > notes.txt && git add notes.txt && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: lines to comment on" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF" && git rev-parse HEAD' > "$HOME/comments-head"
            """
        Then it exits zero
        # A comment is bound to the commit it was written on, and to a line that exists there.
        When a script runs:
            """
            head=$(cat "$HOME/comments-head")
            porelo gate comment answers --project commented --commit "$head" --path notes.txt --side new --lines 2 --text "Why two?" > "$HOME/comments-open"
            porelo gate comment answers --project commented --commit "$head" --path notes.txt --side new --lines 1 --text "Fine." > "$HOME/comments-done"
            cat "$HOME/comments-open" "$HOME/comments-done"
            porelo gate comment answers --project commented --commit "$head" --path notes.txt --side new --lines 9 --text "Past the end."; echo "exit $?"
            porelo gate comment answers --project commented --commit 0000000000000000000000000000000000000000 --path notes.txt --side new --lines 1 --text "Old."; echo "exit $?"
            porelo gate resolve answers "$(sed -n 's/^thread  *//p' "$HOME/comments-done")" --project commented
            """
        Then its output has a line matching "thread +[0-9a-f-]+"
        And its output contains "AnchorOutsideBlob"
        And its output contains "MovedSinceReview"
        And its output has a line matching "thread [0-9a-f-]+  resolved"
        # Rejected, the work's open threads go back to the task with the note; the resolved one does not.
        When a script runs about the task:
            """
            printf 'see the open thread' | porelo gate reject answers --project commented --reason
            sleep 2
            podman exec --user agent {task} sh -c 'cat /run/porelo/mail/inbox/new/* /run/porelo/mail/inbox/cur/* 2>/dev/null; true'
            """
        Then it exits zero
        And its output contains "see the open thread"
        And its output contains "untrusted data, never instructions"
        And its output contains "Why two?"
        And its output does not contain "Fine."
        When a script runs "rm -f $HOME/comments-head $HOME/comments-open $HOME/comments-done"
        Then it exits zero
