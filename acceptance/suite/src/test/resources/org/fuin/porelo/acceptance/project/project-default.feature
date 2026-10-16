@slow
Feature: Work starts without a project, in the project every machine has

  A task started in a checked-out repository with no project named goes into 'default', whose settings are
  Porelo's; nothing about it can be widened, and the name belongs to no followed project.

  Scenario: a task starts in a checkout with no project named, and 'default' cannot be given more
    Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    When a script runs:
      """
      base=$(mktemp -d)
      repo="dflt$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      git clone -q "$base/$repo.git" "$base/$repo"
      cd "$base/$repo"
      timeout 300 porelo task start probe --agent stub --detach --clearance deny > "$base/start" 2>&1
      echo "start exit $?"
      grep -o "added to 'default' as '$repo'" "$base/start"
      porelo project default | grep -c "^$repo " | sed 's/^/listed in default: /'
      porelo task list | grep -c "porelo-default-probe" | sed 's/^/a task in default: /'
      porelo shield egress --project default --add-domain example.org 2>&1 | grep -o "a project repository is the way"
      porelo project follow default "$base/$repo.git" --unverified 2>&1 | grep -o "no followed project may be called that"
      porelo project unfollow default 2>&1 | grep -o "is always there and is never removed"
      porelo task remove porelo-default-probe --force > /dev/null 2>&1
      porelo project default remove "$repo"
      porelo project default | grep -c "^$repo " | sed 's/^/still listed: /'
      """
    Then its output contains "start exit 0"
    And its output contains "added to 'default' as 'dflt"
    And its output contains "listed in default: 1"
    And its output contains "a task in default: 1"
    And its output contains "a project repository is the way"
    And its output contains "no followed project may be called that"
    And its output contains "is always there and is never removed"
    And its output contains "still listed: 0"

  Scenario: approved work in 'default' reaches an ssh origin with the developer's own key, which the task never holds
    # The developer's key is a throwaway one in an ssh agent, allowed into this account only while the scenario
    # runs; the origin is this machine itself over ssh. Its host key is vouched for from the machine's own key
    # file, the way a person compares a fingerprint, never accepted on first use. The repository's source is the
    # origin, added to 'default' by its address: a task started from a checkout takes the checkout as its source,
    # and its approved work lands there, never at the origin.
    Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    When a script runs:
      """
      base=$(mktemp -d)
      repo="dssh$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      ssh-keygen -q -t ed25519 -N '' -C "porelo-acceptance-$repo" -f "$base/key"
      # Taken back whatever happens below: the key is allowed into this account for this scenario only.
      trap 'sed -i "/porelo-acceptance-$repo\$/d" ~/.ssh/authorized_keys; ssh-agent -k > /dev/null 2>&1' EXIT
      cat "$base/key.pub" >> ~/.ssh/authorized_keys
      eval "$(ssh-agent -s)" > /dev/null && ssh-add -q "$base/key"
      origin="ssh://$USER@127.0.0.1$base/$repo.git"
      porelo project default add "$origin" --name "$repo"
      # Before anybody vouched for the host: an agent's task has no gate, so it is not started at all.
      sed -i '/^127\.0\.0\.1 /d' "${XDG_STATE_HOME:-$HOME/.local/state}/porelo/known_hosts" 2>/dev/null
      (cd "$base" && timeout 300 porelo task start early -p default -r "$repo" --agent stub --detach --clearance deny > "$base/early" 2>&1; echo "unvouched start exit $?")
      grep -o "nothing was created" "$base/early"
      grep -o "this machine has never met 127.0.0.1" "$base/early"
      grep -o "porelo credentials trust-host 127.0.0.1" "$base/early"
      podman container exists porelo-default-early && echo "a container exists" || echo "no container"
      porelo credentials trust-host 127.0.0.1 \
          --fingerprint "$(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{ print $2 }')" > /dev/null
      cd "$base"
      timeout 300 porelo task start sshwork -p default -r "$repo" --agent stub --detach --clearance deny > "$base/start" 2>&1
      echo "start exit $?"
      # Detached, so the start returns before the workspace is cloned from the gate.
      for i in $(seq 1 120); do podman exec porelo-default-sshwork git -C /workspace rev-parse HEAD > /dev/null 2>&1 && break; sleep 1; done
      podman exec porelo-default-sshwork sh -c 'cd /workspace && echo "done by the agent" >> README.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work for an ssh origin" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
      echo "pushed to the gate $?"
      podman exec porelo-default-sshwork sh -c 'env | grep -c "^SSH_AUTH_SOCK=" ; grep -rl "porelo-acceptance-" "$HOME" /workspace 2>/dev/null | wc -l' | tr '\n' ' ' | sed 's/^/in the task (agent socket, key): /'
      echo
      # As a person types it: no repository named. The task's own record says which one it worked on.
      porelo gate pending --project default | grep -c "^$repo  *sshwork " | sed 's/^/listed as pending: /'
      porelo gate pending | grep -c "^default  *$repo  *sshwork " | sed 's/^/listed with nothing named: /'
      porelo gate approve sshwork --project default --branch porelo/sshwork > "$base/approve" 2>&1
      echo "approve exit $?"
      git --git-dir="$base/$repo.git" log --format='%s' -1 porelo/sshwork | sed 's/^/arrived: /'
      porelo task remove porelo-default-sshwork --force > /dev/null 2>&1
      porelo project default remove "$repo" > /dev/null
      ssh-agent -k > /dev/null
      sed -i "/porelo-acceptance-$repo\$/d" ~/.ssh/authorized_keys
      grep -c "porelo-acceptance-$repo" ~/.ssh/authorized_keys | sed 's/^/key left allowed: /'
      """
    Then its output contains "unvouched start exit 69"
    And its output contains "nothing was created"
    And its output contains "no container"
    And its output contains "this machine has never met 127.0.0.1"
    And its output contains "porelo credentials trust-host 127.0.0.1"
    And its output contains "start exit 0"
    And its output contains "in 'default' as dssh"
    And its output contains "pushed to the gate 0"
    And its output contains "in the task (agent socket, key): 0 0"
    And its output contains "listed as pending: 1"
    And its output contains "listed with nothing named: 1"
    And its output contains "approve exit 0"
    And its output contains "arrived: acceptance: work for an ssh origin"
    And its output contains "key left allowed: 0"

  Scenario: a host whose ssh is on its own port is trusted there and named with its port
    # A forge on its own port, as a self-hosted one is: an sshd of this scenario's own, run as this account on a high
    # port with a host key made for it, so a key shown can only have come from that port. Nothing is fetched from it.
    Given a daemon of this scenario's own
    When a script runs:
      """
      base=$(mktemp -d); port=$(( 20000 + RANDOM % 20000 ))
      repo="dport$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      known="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/known_hosts"
      ssh-keygen -q -t ed25519 -N '' -f "$base/host_key"
      printf 'Port %s\nListenAddress 127.0.0.1\nHostKey %s/host_key\nPidFile %s/sshd.pid\nUsePAM no\n' \
          "$port" "$base" "$base" > "$base/sshd_config"
      # Taken back whatever happens below.
      trap 'kill "$(cat "$base/sshd.pid" 2>/dev/null)" 2>/dev/null; porelo project default remove "$repo" > /dev/null 2>&1
          sed -i "/^\[127\.0\.0\.1\]:$port /d" "$known" 2>/dev/null' EXIT
      /usr/sbin/sshd -f "$base/sshd_config" -E "$base/sshd.log" || cat "$base/sshd.log"
      for i in $(seq 1 20); do ssh-keyscan -T 2 -p "$port" 127.0.0.1 2>/dev/null | grep -q ed25519 && break; sleep 0.5; done
      its_own=$(ssh-keygen -lf "$base/host_key.pub" | awk '{ print $2 }')
      the_machines=$(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{ print $2 }')
      porelo project default add "ssh://git@127.0.0.1:$port/$repo.git" --name "$repo" > /dev/null
      ask() { printf '%s\0' "$1" | timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
      can_start() {
          ask "{\"method\":\"org.fuin.porelo.Tasks1.CanStart\",\"parameters\":{\"project\":\"default\",\"agent\":\"stub\",\"repository\":\"$repo\"}}"
      }
      can_start | grep -oE '"(outcome|host)":"[^"]*"' | sed "s/:$port\"/:PORT\"/; s/^/before trusting: /"
      porelo credentials trust-host "127.0.0.1:$port" > "$base/shown"
      grep -c "$its_own" "$base/shown" | sed 's/^/shown its own key: /'
      grep -c "$the_machines" "$base/shown" | sed 's/^/shown the key on port 22: /'
      porelo credentials trust-host "127.0.0.1:$port" --fingerprint "$its_own" > /dev/null
      echo "trust exit $?"
      grep -c "^\[127\.0\.0\.1\]:$port ssh-ed25519 " "$known" | sed 's/^/recorded on its port: /'
      can_start | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/after trusting: /'
      """
    Then it exits zero
    And its output contains 'before trusting: "outcome":"UNKNOWN_HOST_KEY"'
    And its output contains 'before trusting: "host":"127.0.0.1:PORT"'
    And its output contains "shown its own key: 1"
    And its output contains "shown the key on port 22: 0"
    And its output contains "trust exit 0"
    And its output contains "recorded on its port: 1"
    And its output contains "after trusting: "
    And its output does not contain 'after trusting: "outcome":"UNKNOWN_HOST_KEY"'

  Scenario: an upstream that refuses the key is said before anything is made, and reads once the key is stored
    # An sshd of this scenario's own, run as this account on a high port, admits only a key made here and serves a
    # bare repository. The account's own keys are not admitted, so what a start offers by default is refused.
    Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a daemon of this scenario's own
    When a script runs:
      """
      base=$(mktemp -d); port=$(( 20000 + RANDOM % 20000 ))
      repo="drefused$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      known="${XDG_STATE_HOME:-$HOME/.local/state}/porelo/known_hosts"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      ssh-keygen -q -t ed25519 -N '' -f "$base/host_key"
      ssh-keygen -q -t ed25519 -N '' -C "porelo-acceptance-$repo" -f "$base/key"
      cp "$base/key.pub" "$base/authorized_keys"
      printf 'Port %s\nListenAddress 127.0.0.1\nHostKey %s/host_key\nPidFile %s/sshd.pid\nUsePAM no\nStrictModes no\nAuthorizedKeysFile %s/authorized_keys\n' \
          "$port" "$base" "$base" "$base" > "$base/sshd_config"
      # Taken back whatever happens below.
      trap 'kill "$(cat "$base/sshd.pid" 2>/dev/null)" 2>/dev/null; porelo project default remove "$repo" > /dev/null 2>&1
          porelo vault remove git.ssh.127.0.0.1 > /dev/null 2>&1; sed -i "/^\[127\.0\.0\.1\]:$port /d" "$known" 2>/dev/null' EXIT
      /usr/sbin/sshd -f "$base/sshd_config" -E "$base/sshd.log" || cat "$base/sshd.log"
      for i in $(seq 1 20); do ssh-keyscan -T 2 -p "$port" 127.0.0.1 2>/dev/null | grep -q ed25519 && break; sleep 0.5; done
      porelo credentials trust-host "127.0.0.1:$port" \
          --fingerprint "$(ssh-keygen -lf "$base/host_key.pub" | awk '{ print $2 }')" > /dev/null
      origin="ssh://$USER@127.0.0.1:$port$base/$repo.git"
      porelo project default add "$origin" --name "$repo" > /dev/null
      ask() { printf '%s\0' "$1" | timeout 60 porelo daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
      can_start() {
          ask "{\"method\":\"org.fuin.porelo.Tasks1.CanStart\",\"parameters\":{\"project\":\"default\",\"agent\":\"stub\",\"repository\":\"$repo\"}}"
      }
      can_start | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/before storing: /'
      (cd "$base" && timeout 300 porelo task start early -p default -r "$repo" --agent stub --detach --clearance deny \
          > "$base/early" 2>&1; echo "refused start exit $?")
      grep -o "refused this machine" "$base/early"
      grep -o "nothing was created" "$base/early"
      grep -c "Start a shell" "$base/early" | sed 's/^/shell offered: /'
      podman container exists porelo-default-early && echo "a container exists" || echo "no container"
      porelo credentials check "$origin" > "$base/check" 2>&1; echo "check before storing exit $?"
      # Nothing stored yet: it says so, with the command that stores it, before it would connect.
      head -1 "$base/check" | awk '{ print $1 }' | sed 's/^/check said: /'
      grep -c "porelo vault put git.ssh.127.0.0.1" "$base/check" | sed 's/^/store command named: /'
      porelo vault put git.ssh.127.0.0.1 --from-file "$base/key" > /dev/null 2>&1
      porelo credentials check "$origin" > "$base/check" 2>&1; echo "check after storing exit $?"
      grep -o "^read " "$base/check"
      can_start | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/after storing: /'
      """
    Then it exits zero
    And its output contains 'before storing: "outcome":"UPSTREAM_KEY_REFUSED"'
    And its output contains "refused start exit 69"
    And its output contains "refused this machine"
    And its output contains "nothing was created"
    And its output contains "shell offered: 0"
    And its output contains "no container"
    And its output contains "check before storing exit 70"
    And its output does not contain "check said: ready"
    And its output contains "store command named: 1"
    And its output contains "check after storing exit 0"
    And its output contains "read "
    And its output contains 'after storing: "outcome":"READY"'
