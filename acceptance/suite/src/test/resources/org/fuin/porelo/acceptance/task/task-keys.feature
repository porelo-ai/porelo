@slow
Feature: The keys a person presses reach the program in a task
  # --agent is named because this machine may have more than one installed. Which agent is in the image is
  # irrelevant here: a program in the task's shell stands in for it and prints, in hex, the bytes that reached it.

  The task's tmux sits between the person's terminal and the agent. What it passes on, and what it turns a key into,
  decides whether Esc interrupts at once and whether Shift+Enter reaches the agent at all. Each key is pressed as the
  bytes an xterm-256color sends, and the program behind tmux has to receive what the measurement of 2026-10-09 says.

  Each line that waits for a key first prints a READY mark built at run time, so the key is pressed only once the
  program reads, and the mark it prints afterwards is not in the command the shell echoes.

  The scenario removes its task at the end, as the other scenarios inside a task do.

  Scenario: each key arrives at the program behind tmux as the terminal outside sent it
    Given a project called "keys" with a file in it
    And a terminal on the machine
    When I run "porelo task start keys --project keys --repository keys --agent stub --attach shell"
    And I wait for the shell inside the container
    # Esc: at once, since tmux's escape-time is 10 ms; its timing stays with ContainerfileTest.
    When I run "stty raw -echo; printf 'READY-%s ' K1; head -c 1 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K1; stty sane"
    And the terminal shows "READY-K1"
    And I press the key "Esc"
    Then within 5 seconds the terminal shows "1b.K1"
    # PageUp: the program's, not tmux's history, since it is not Shift+PageUp.
    When I run "stty raw -echo; printf 'READY-%s ' K2; head -c 4 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K2; stty sane"
    And the terminal shows "READY-K2"
    And I press the key "PageUp"
    Then within 5 seconds the terminal shows "1b5b357e.K2"
    When I run "stty raw -echo; printf 'READY-%s ' K3; head -c 6 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K3; stty sane"
    And the terminal shows "READY-K3"
    And I press the key "Ctrl+Right"
    Then within 5 seconds the terminal shows "1b5b313b3543.K3"
    When I run "stty raw -echo; printf 'READY-%s ' K4; head -c 2 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K4; stty sane"
    And the terminal shows "READY-K4"
    And I press the key "Alt+x"
    Then within 5 seconds the terminal shows "1b78.K4"
    # Shift+Enter: the CSI u form, as sent.
    When I run "stty raw -echo; printf 'READY-%s ' K5; head -c 7 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K5; stty sane"
    And the terminal shows "READY-K5"
    And I press the key "Shift+Enter"
    Then within 5 seconds the terminal shows "1b5b31333b3275.K5"
    # Shift+Enter, xterm's form: arrives in the CSI u form, as measured on 2026-10-09.
    When I run "stty raw -echo; printf 'READY-%s ' K6; head -c 7 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' K6; stty sane"
    And the terminal shows "READY-K6"
    And I press the key "Shift+Enter, xterm's form"
    Then within 5 seconds the terminal shows "1b5b31333b3275.K6"
    # focus in: reaches only a program that asked for focus events, as an agent's interface does.
    When I run "stty raw -echo; awk 'BEGIN { printf \"%c[?1004h\", 27 }'; printf 'READY-%s ' F1; head -c 3 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' F1; stty sane"
    And the terminal shows "READY-F1"
    And I press the key "focus in"
    Then within 5 seconds the terminal shows "1b5b49.F1"
    When I run "stty raw -echo; awk 'BEGIN { printf \"%c[?1004h\", 27 }'; printf 'READY-%s ' F2; head -c 3 | od -An -tx1 | tr -dc 0-9a-f; printf '.%s ' F2; stty sane"
    And the terminal shows "READY-F2"
    And I press the key "focus out"
    Then within 5 seconds the terminal shows "1b5b4f.F2"
    When I run "exit"
    Then the terminal shows "ready$"
    And a script runs "porelo task remove porelo-keys-keys --force"
