Feature: A task is what Porelo made

  Anything that runs as the account can start a container with any name and any label. So a container is a task
  only when its id is the one Porelo recorded when it created it - 2026-10-04: "Porelo must not pick
  running containers by their name. Anybody could start such a container and Porelo would think it is its own."

  Scenario: a container named and labelled like a task is no task, and nothing is done to it
    # Any image the machine already holds will do; what matters is the name and the labels it is given.
    When a script runs "podman rm -f porelo-forged-task >/dev/null 2>&1; podman run -d --name porelo-forged-task --label org.fuin.porelo.project=forged --label org.fuin.porelo.class=guarded --label org.fuin.porelo.security-class=guarded --entrypoint sleep $(podman images --format '{{.Repository}}:{{.Tag}}' | grep -v '<none>' | head -1) 600 >/dev/null && echo started"
    Then it exits zero
    And its output contains "started"
    # Not a row of the table, but named under it as what it is: decided on 2026-10-10, so that "No tasks" never hides a
    # container whose removal removes what is in it.
    When a script runs "porelo task list; porelo task list | grep -c '^porelo-forged-task'; echo rows $?"
    Then its output contains "not listed: porelo-forged-task"
    And its output contains "no container id"
    And its output contains "rows 1"
    When a script runs "porelo task stop porelo-forged-task"
    Then it exits non-zero
    When a script runs "podman ps --format '{{.Names}}' | grep -c '^porelo-forged-task$'"
    Then its output contains "1"
    When a script runs "podman rm -f porelo-forged-task >/dev/null && echo gone"
    Then it exits zero
    And its output contains "gone"
