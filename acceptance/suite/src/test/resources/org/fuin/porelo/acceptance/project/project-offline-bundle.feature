@slow
Feature: An offline project comes in as a file and leaves as one

  An offline project makes no connection out, neither from its task nor from the host on its behalf. Its
  definition comes as a bundle a person carried to the machine, its code is seeded into the gate from another,
  and the work leaves as a bundle too. Nothing is fetched for it, and an address is refused even when it is a
  file:// one on this machine, since an address is what a fetch in the background would follow.

  # Guards a connection an offline project must never make (its code would leave or come in unreviewed), and the
  # work being lost: what a task pushed must be in the bundle that carries it out.
  Scenario: a project followed from a bundle and seeded from one starts a task, and its work is backed up
    Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    # Made here rather than with the kit's project step, which follows a directory: this one is followed from a
    # bundle, the one file a person carries in. What an earlier run left is cleared first.
    When a script runs:
      """
      carried="$HOME/.porelo-acceptance-carried"
      porelo project unfollow carried --force >/dev/null 2>&1
      rm -rf "$carried" "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/mirrors/carried.git"
      mkdir -p "$carried" && git init -q -b main "$carried/repo" && cd "$carried/repo" || exit 1
      git config user.email t@example.com && git config user.name T
      printf '%s\n' 'project:' '  name: "carried"' '  security_class: "offline"' 'image:' \
          '  base_image: "ubuntu:24.04"' > project.yml
      echo 'the project' > README.md
      git add -A && git commit -q -m definition && git bundle create "$carried/definition.bundle" HEAD main 2>/dev/null
      echo 'the code' > code.txt
      git add -A && git commit -q -m code && git bundle create "$carried/code.bundle" HEAD main 2>/dev/null
      porelo project follow carried "$carried/definition.bundle" --unverified; echo "follow exit $?"
      porelo gate restore "$carried/code.bundle" --project carried; echo "restore exit $?"
      """
    Then its output contains "follow exit 0"
    And its output contains "restore exit 0"
    When a task called "carried" is started in "carried" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && cat code.txt && echo work > WORK.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work carried out" && timeout 30 git push -q porelo HEAD:"$PORELO_TASK_REF"'
      """
    Then it exits zero
    And its output contains "the code"
    When a script runs about the task:
      """
      carried="$HOME/.porelo-acceptance-carried"
      commit=$(podman exec {task} git -C /workspace rev-parse HEAD)
      porelo gate backup "$carried/out.bundle" --project carried
      git bundle list-heads "$carried/out.bundle" | grep -q "^$commit " && echo "the backup holds the task's commit"
      """
    Then its output contains "verified  true"
    And its output contains "the backup holds the task's commit"
    # Nothing to fetch, and nothing that may be; not a failure either.
    When a script runs "porelo project refresh carried"
    Then it exits zero
    And its output contains "from_a_file"
    When a script runs:
      """
      porelo project follow carried-by-address "file://$HOME/.porelo-acceptance-carried/repo" --unverified
      echo "follow exit $?"
      porelo project following | grep -c 'carried-by-address' | sed 's/^/listed: /'
      """
    # Refused because it is offline, not for some other reason a follow can fail for.
    Then its output contains "an offline project never connects"
    And its output does not contain "follow exit 0"
    And its output contains "listed: 0"
    And a script runs about the task:
      """
      porelo task remove {task} --force
      porelo project unfollow carried --force
      rm -rf "$HOME/.porelo-acceptance-carried"
      """
