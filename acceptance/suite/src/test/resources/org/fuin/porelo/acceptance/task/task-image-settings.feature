@slow
Feature: A task's image says what it was built with

    What a window shows of a task's image comes from a label Porelo writes as the image's last line, and
    the daemon answers it by the image the container was made from. A unit test sees neither a real label
    nor a real build, and a secret in a build line must not come back through either.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" is set
        And a vault of this scenario's own, unlocked with the passphrase "acceptance"
        And the vault holds the value of "PORELO_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
        And a project called "imagesettings" of class "guarded" whose project file also says:
            """
              run: |
                ENV IMAGE_SETTINGS_CANARY=settings-canary-value
                RUN apt-get update && apt-get install -y --no-install-recommends jq \
                    && rm -rf /var/lib/apt/lists/*
            """
        And a daemon of this scenario's own

    Scenario: a started task answers what its image holds, with its sources and without the secret
        When a task called "settings" is started in "imagesettings" for the "stub" agent and left running
        And a script runs about the task:
            """
            cat > /tmp/image-settings-$$.py <<'PY'
            import json, subprocess, sys
            connection = subprocess.Popen(["porelo", "daemon", "connect"], stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            connection.stdin.write(json.dumps({"method": "org.fuin.porelo.Tasks1.ImageSettings",
                "parameters": {"task": sys.argv[1]}}).encode() + b"\0")
            connection.stdin.flush()
            buffer = b""
            while True:
                byte = connection.stdout.read(1)
                if not byte or byte == b"\0":
                    break
                buffer += byte
            connection.terminate()
            reply = json.loads(buffer)
            answer = reply.get("parameters", {})
            print("error:", reply.get("error"))
            print("known:", answer.get("known"))
            print("package:", [(each["value"], each["source"]) for each in answer.get("packages", [])])
            print("lines:", [each["value"] for each in answer.get("lines", [])])
            print("agent:", answer.get("agent", {}).get("value"), "from", answer.get("agent", {}).get("source"))
            print("security class:", answer.get("securityClass", {}).get("value"))
            print("recorded given:", bool(answer.get("recorded")))
            print("canary shown:", "settings-canary-value" in buffer.decode())
            PY
            python3 /tmp/image-settings-$$.py {task}
            rm -f /tmp/image-settings-$$.py
            image=$(podman container inspect --format '{{.Image}}' {task})
            podman image inspect --format '{{index .Labels "org.fuin.porelo.settings"}}' "$image" \
                | grep -c settings-canary-value | sed 's/^/canary in the label: /'
            porelo project show default --json > /dev/null
            echo "show default exit $?"
            """
        Then its output contains "error: None"
        And its output contains "known: True"
        And its output contains "'jq'"
        And its output contains "IMAGE_SETTINGS_CANARY=<withheld>"
        And its output contains "agent: stub from agent package"
        And its output contains "security class: guarded"
        And its output contains "recorded given: True"
        And its output contains "canary shown: False"
        And its output contains "canary in the label: 0"
        And its output contains "show default exit 0"
