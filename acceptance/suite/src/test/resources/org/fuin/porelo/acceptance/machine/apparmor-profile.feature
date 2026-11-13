@slow @restart @machine-package
Feature: The AppArmor profile a task runs in, on a machine Porelo was installed on for everyone

    Where AppArmor runs, a task's agent is put into porelo-task, which may connect to no unix socket
    whose peer is not one of Porelo's binaries. Porelo's binaries wear the label porelo by the path
    the package puts them at, so the lock holds for the machine-wide install only: an account's
    copy cannot take that label by name where the kernel stacks it, and doctor says so rather than
    promising a lock that is not there.

    Run where Porelo's package is installed and nothing in the account shadows it, which no CI leg
    is, so the legs leave it out; on a machine without AppArmor every scenario here is skipped. Tagged restart because it reboots the machine
    to see the boot load the profile again, and because what it asks of is the whole machine's.

    Background:
        Given the machine runs AppArmor
        And Porelo is installed machine-wide by its package

    Scenario: the package's task profile is loaded under abi 5.0, compiles, and is loaded again after a restart
        # Unprivileged: the profile's file and the kernel's per-profile directories are readable by
        # anyone, and -Q -K compiles without loading anything or writing a cache.
        When a script runs:
            """
            PATH="$PATH:/usr/sbin:/sbin"
            profile=/etc/apparmor.d/porelo-task
            dpkg-query -S "$profile" 2>/dev/null | grep -q '^porelo:' && echo "the package put $profile there"
            grep -qx 'abi <abi/5.0>,' "$profile" && echo "it is written under abi <abi/5.0>"
            for d in /sys/kernel/security/apparmor/policy/profiles/porelo-task.*; do
              [ "$(cat "$d/name" 2>/dev/null)" = porelo-task ] && echo "the kernel holds porelo-task in $(cat "$d/mode") mode"
            done
            apparmor_parser -Q -K "$profile" && echo "apparmor_parser compiles it"
            """
        Then its output contains "the package put /etc/apparmor.d/porelo-task there"
        And its output contains "it is written under abi <abi/5.0>"
        And its output contains "the kernel holds porelo-task in enforce mode"
        And its output contains "apparmor_parser compiles it"
        # The package's postinst loaded it once; from here on it is the boot that has to.
        When the machine restarts
        When a script runs:
            """
            for d in /sys/kernel/security/apparmor/policy/profiles/porelo*; do
              name=$(cat "$d/name" 2>/dev/null) || continue
              case $name in porelo|porelo-task) echo "after the restart the kernel holds $name in $(cat "$d/mode") mode" ;; esac
            done
            """
        Then it exits zero
        And its output contains "after the restart the kernel holds porelo-task in enforce mode"
        And its output has a line matching "after the restart the kernel holds porelo in \w+ mode"

    Scenario: doctor says the lock holds for the machine-wide install
        When a script runs "porelo doctor"
        Then its output has a line matching "apparmor profile +installed - .*the lock holds here.*"

    Scenario: doctor says the lock is not held for an account install, and why
        # An account install is the package's own binary in the account's directories, which is what
        # the deploy's --account does: the same file, run from a path no profile attaches to. It
        # tries to enter porelo by name, and where unprivileged processes are restricted the kernel
        # stacks the label instead. The restriction is printed, so a failure says which case it met.
        When a script runs:
            """
            account=$(mktemp -d "$HOME/.porelo-account-XXXXXX")
            cp /usr/bin/porelo "$account/porelo"
            echo "unprivileged unconfined restricted: $(cat /proc/sys/kernel/apparmor_restrict_unprivileged_unconfined 2>/dev/null || echo unknown)"
            "$account/porelo" doctor 2>&1 | grep -A1 '^apparmor profile'
            rm -rf "$account"
            """
        Then its output has a line matching "apparmor profile +DEGRADED - loaded, but this binary is not in plain porelo \(.+\); the lock is not held here; an account install .*"
        And its output has a line matching "-> use the machine-wide package.*"

    Scenario: a task's agent runs in porelo-task and reaches the vault proxy through its socket
        Given the suite runs as an unprivileged user
        And the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a script runs "porelo task remove porelo-armored_kept --force; porelo task remove porelo-armored_asked --force"
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "armored" of class "guarded" with a file in it
        When a task called "kept" is started in "armored" for the "stub" agent and left running
        # A detached start runs no agent: attaching starts its session, and the stub, in a terminal and
        # asked nothing, waits for input - an agent process that stays to be read.
        And a terminal on the machine
        And I run "porelo task attach porelo-armored_kept"
        Then the terminal shows "at work, nothing asked"
        # Read as the container's root, which sees every process of the task: the agent is in the
        # profile, put there by Porelo's start rather than by the container's own default.
        When a script runs about the task:
            """
            podman exec --user root {task} sh -c 'for p in /proc/[0-9]*; do
              l=$(tr -d "\000\n" < "$p/attr/apparmor/current" 2>/dev/null) || continue
              echo "$l | $(tr "\000" " " < "$p/cmdline" 2>/dev/null)"
            done'
            """
        Then it exits zero
        And its output has a line matching "porelo-task \(enforce\) \| .*porelo-stub-cli.*"
        When I log off
        And the task's container runs:
            """
            [ -S /run/porelo-vault/vault.sock ] && echo "a socket at /run/porelo-vault/vault.sock"
            echo "the agent is given $PORELO_STUB_SOCKET"
            """
        Then its output contains "a socket at /run/porelo-vault/vault.sock"
        And its output contains "the agent is given /run/porelo-vault/vault.sock"
        # The agent's own unattended run, which Porelo puts into porelo-task as it does the session: its last
        # act is one call to its provider through that socket, and only a call that got through the vault
        # proxy comes back with the provider's refusal of the fake key.
        When a script runs:
            """
            timeout 900 porelo task start asked --project armored --repository armored --agent stub -P 'say hello' \
                --detach --clearance deny >/dev/null 2>&1; echo "start exit $?"
            for i in $(seq 1 45); do
              said=$(porelo task status porelo-armored_asked 2>&1)
              echo "$said" | grep -q '^ended' && break
              sleep 2
            done
            echo "$said" | grep -E '^(activity|ended) '
            """
        Then its output contains "start exit 0"
        And its output has a line matching "ended +with an error at .*: the provider - .+"
        And a script runs "porelo task remove porelo-armored_kept --force; porelo task remove porelo-armored_asked --force"

    Scenario: what runs in porelo-task cannot connect to a socket that no Porelo binary serves
        # The effect the profile exists for. A tmux server started by a plain exec of the agent's own
        # user is outside porelo-task and wears no porelo label: the same user's connect reaches it from
        # a plain exec, and is refused from the task's session, which Porelo put into porelo-task. A
        # profile weakened to a bare unix rule would let both through.
        Given the suite runs as an unprivileged user
        And a script runs "porelo task remove porelo-armored_fenced --force"
        And a project called "armored" of class "guarded" with a file in it
        And a terminal on the machine
        When I run "porelo task start fenced --project armored --repository armored --agent stub --attach shell --detach --clearance deny"
        And a script runs:
            """
            podman exec --user agent porelo-armored_fenced sh -c \
                'mkdir -p /tmp/foreign && tmux -S /tmp/foreign/sock new-session -d sleep 3600'
            podman exec --user agent porelo-armored_fenced tmux -S /tmp/foreign/sock list-sessions >/dev/null \
                && echo "outside porelo-task the agent's user reaches it"
            """
        Then its output contains "outside porelo-task the agent's user reaches it"
        When I run "porelo task attach porelo-armored_fenced"
        And I wait for the session inside the container
        And I run "cat /proc/self/attr/apparmor/current; echo; tmux -S /tmp/foreign/sock list-sessions >/dev/null 2>&1 && echo FOREIGN-''REACHED || echo FOREIGN-''REFUSED"
        # Split in what is typed, so the echo of the line itself matches neither.
        Then the terminal shows "porelo-task (enforce)"
        And the terminal shows "FOREIGN-REFUSED"
        When I log off
        And a script runs "porelo task remove porelo-armored_fenced --force"
