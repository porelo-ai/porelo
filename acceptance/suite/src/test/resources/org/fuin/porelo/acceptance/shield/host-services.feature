@slow @host-services
Feature: A declared name reaches a service on this host on TCP 443

    This needs a VM with pasta and a root-run TLS listener bound to exactly 127.0.0.1:443.
    Prepare it outside the suite: its certificate names forge.host-service.test, GET / returns
    host-service-ok, and its public CA is readable at /var/tmp/porelo-host-services/ca.pem.
    Set PORELO_ACCEPTANCE_HOST_SERVICES on the suite runner only for that prepared VM.
    The kit's environment-variable step marks this skipped where the listener is unavailable;
    The tag host-services also allows explicit selection with cucumber.filter.tags.

    Background:
        Given the environment variable "PORELO_ACCEPTANCE_HOST_SERVICES" is set
        And the suite runs as an unprivileged user

    Scenario Outline: a declared host service is reachable while other host ports stay closed
        Given a project called "host-service-<class>" of class "<class>" whose project file also says:
            """
            egress:
              host_services: [forge.host-service.test]
            """
        When a task called "service" is started in "host-service-<class>" for the "stub" agent and left running
        And a script runs about the task:
            """
            podman cp /var/tmp/porelo-host-services/ca.pem {task}:/tmp/host-services-ca.pem
            """
        Then it exits zero
        When the task's container runs:
            """
            set -eu
            getent ahostsv4 forge.host-service.test | awk '{print $1}' | sort -u
            curl --fail --silent --show-error --max-time 10 --noproxy '*' \
                --cacert /tmp/host-services-ca.pem https://forge.host-service.test/
            if getent hosts undeclared.host-service.test; then
                echo "an undeclared name resolved"
                exit 1
            fi
            echo "the undeclared name did not resolve"
            """
        Then it exits zero
        And its output contains "169.254.1.2"
        And its output contains "host-service-ok"
        And its output contains "the undeclared name did not resolve"
        When a script runs about the task:
            """
            podman exec {task} bash -c 'timeout 5 bash -c "echo > /dev/tcp/169.254.1.2/80"' 2>/dev/null
            rc=$?
            [ "$rc" = 124 ] || { echo "expected a logged drop on host port 80, got $rc"; exit 1; }
            sleep 2
            grep '"destination":"169.254.1.2","port":80' {state}/events.jsonl
            """
        Then it exits zero
        And its output contains "169.254.1.2"

        Examples:
            | class   |
            | guarded |
            | offline |
