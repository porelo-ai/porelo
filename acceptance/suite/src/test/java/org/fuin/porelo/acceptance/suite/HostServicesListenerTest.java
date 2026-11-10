package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostServicesListenerTest {
    @TempDir Path dir;
    private String sockets = "";
    private int ssStatus;
    private int stopStatus;
    private boolean active;
    private boolean sign;
    private int uid;
    private int startStatus;

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:443", "0.0.0.0:443", "*:443", "[::]:443"})
    void refusesAnOccupiedPortBeforeChangingTheMachine(String address) throws Exception {
        // Taking an occupied port must not overwrite certificates or disturb another service.
        sockets = "LISTEN 0 128 " + address + " 0.0.0.0:*";
        assertThat(run("up")).as("occupied port is refused").isNotZero();
        assertUntouched();
    }

    @Test
    void refusesAnUnreadableSocketInventory() throws Exception {
        // A failed inventory must not be mistaken for a free port.
        ssStatus = 1;
        assertThat(run("up")).as("failed ss is refused").isNotZero();
        assertUntouched();
    }

    @Test
    void refusesASecondUpBeforeChangingKeys() throws Exception {
        // Replacing a running listener's key would break its CA and leave a second instance.
        active = true;
        assertThat(run("up")).as("running unit is refused").isNotZero();
        assertUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "up extra", "down extra"})
    void refusesInvalidArgumentsWithoutChangingTheMachine(String args) throws Exception {
        // An invalid command must not start a service or erase its keys.
        assertThat(run(args.isEmpty() ? new String[0] : args.split(" ")))
                .as("invalid arguments are refused")
                .isNotZero();
        assertUntouched();
    }

    @Test
    void downPreservesMaterialWhenStoppingFails() throws Exception {
        // Deleting keys while the listener is still running leaves an unmanaged service.
        Files.createDirectories(dir.resolve("private"));
        Files.writeString(dir.resolve("private/server.key"), "test material");
        stopStatus = 1;
        assertThat(run("down")).as("a real stop failure is reported").isNotZero();
        assertThat(dir.resolve("private/server.key")).as("failed stop preserves files").exists();
    }

    @Test
    void downSucceedsWhenNothingExists() throws Exception {
        // Cleanup after a partial preparation must not fail just because the unit is absent.
        stopStatus = 5;
        assertThat(run("down")).as("absent unit is already down").isZero();
    }

    @Test
    void failedPreparationRemovesOnlyItsOwnFiles() throws Exception {
        // A signing failure must not leave a CA key or stale directories for the next run.
        assertThat(run("up")).as("fake openssl fails").isNotZero();
        assertThat(dir.resolve("private"))
                .as("failed preparation removes private material")
                .doesNotExist();
        assertThat(dir.resolve("public"))
                .as("failed preparation removes its CA directory")
                .doesNotExist();
    }

    @Test
    void refusesLeftoversWithoutOverwritingThem() throws Exception {
        // Reusing an existing directory can overwrite another run's keys or follow its links.
        Files.createDirectories(dir.resolve("private"));
        Files.writeString(dir.resolve("private/server.key"), "previous run");
        assertThat(run("up")).as("leftover directory is refused").isNotZero();
        assertThat(dir.resolve("private/server.key"))
                .as("previous material survives")
                .hasContent("previous run");
        assertThat(dir.resolve("mutations"))
                .as("no certificate or unit command ran")
                .doesNotExist();
    }

    @Test
    void refusesAnUnprivilegedCaller() throws Exception {
        // An unprivileged invocation must never reach the machine-changing commands.
        uid = 1000;
        assertThat(run("up")).as("root is required").isNotZero();
        assertUntouched();
    }

    @Test
    void preparesPrivateMaterialAndRemovesItOnDown() throws Exception {
        // Exposed private keys or a retained CA signing key let another account impersonate the
        // service.
        sign = true;
        assertThat(run("up")).as(output()).isZero();
        assertThat(Files.getPosixFilePermissions(dir.resolve("private")))
                .as("private directory is root-only")
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(dir.resolve("private/server.key")))
                .as("server key is root-only")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dir.resolve("public/ca.pem")))
                .as("the suite can read the public CA")
                .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(dir.resolve("private/ca.key")).as("CA key was deleted").doesNotExist();
        assertThat(dir.resolve("ca-key-at-launch"))
                .as("CA key was gone before launch")
                .doesNotExist();
        assertThat(Files.readString(dir.resolve("launch")))
                .as("only the named transient unit runs the isolated interpreter")
                .contains(
                        "--unit=porelo-host-services-listener.service",
                        "--collect",
                        "/usr/bin/python3 -I");
        assertThat(Files.readString(dir.resolve("extensions")))
                .as("the certificate names exactly the test host")
                .isEqualTo(
                        "subjectAltName=DNS:forge.host-service.test\nextendedKeyUsage=serverAuth\n");
        assertThat(run("down")).as(output()).isZero();
        assertThat(dir.resolve("private")).as("private material removed").doesNotExist();
        assertThat(dir.resolve("public")).as("public CA removed").doesNotExist();
        assertThat(dir.resolve("started")).as("the fake unit was stopped").doesNotExist();
        assertThat(run("down")).as("a second down is harmless").isZero();
    }

    @Test
    void aFailedUnitStartIsStoppedAndItsKeysRemoved() throws Exception {
        // A failed systemd-run can already have created a unit, which must not outlive preparation.
        sign = true;
        startStatus = 1;
        assertThat(run("up")).as("start failure is reported").isNotZero();
        assertThat(dir.resolve("started")).as("partly started unit stopped").doesNotExist();
        assertThat(dir.resolve("private")).as("private material removed").doesNotExist();
        assertThat(dir.resolve("public")).as("public CA removed").doesNotExist();
    }

    private String output() throws Exception {
        return Files.readString(dir.resolve("output"));
    }

    private void assertUntouched() {
        assertThat(dir.resolve("public")).as("no public directory created").doesNotExist();
        assertThat(dir.resolve("private")).as("no private directory created").doesNotExist();
        assertThat(dir.resolve("mutations")).as("no privileged mutation called").doesNotExist();
    }

    private int run(String... args) throws Exception {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        fake(bin, "id", "echo " + uid);
        fake(
                bin,
                "ss",
                "if [ -e '"
                        + dir.resolve("started")
                        + "' ]; then echo 'LISTEN 0 128 127.0.0.1:443 0.0.0.0:*'; else printf '%s\\n' '"
                        + sockets
                        + "'; exit "
                        + ssStatus
                        + "; fi");
        fake(
                bin,
                "systemctl",
                "case $1 in show) echo "
                        + (active ? "loaded" : "not-found")
                        + ";; is-active) [ ! -e '"
                        + dir.resolve("started")
                        + "' ] || exit 0; exit "
                        + (active ? 0 : 3)
                        + ";; stop) "
                        + (stopStatus == 0 ? "rm -f '" + dir.resolve("started") + "'; " : "")
                        + "exit "
                        + stopStatus
                        + ";; *) exit 0;; esac");
        fake(bin, "stat", "echo 0");
        fake(
                bin,
                "openssl",
                sign
                        ? """
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        -keyout|-out) shift; : > "$1" ;;
                        -extfile) shift; cp "$1" '%s' ;;
                    esac
                    shift
                done
                """
                                .formatted(dir.resolve("extensions"))
                        : "echo called >> '" + dir.resolve("mutations") + "'; exit 1");
        fake(
                bin,
                "systemd-run",
                """
                printf '%%s\\n' "$*" > '%s'
                if [ -e '%s' ]; then : > '%s'; fi
                : > '%s'
                exit %s
                """
                        .formatted(
                                dir.resolve("launch"),
                                dir.resolve("private/ca.key"),
                                dir.resolve("ca-key-at-launch"),
                                dir.resolve("started"),
                                startStatus));
        final Path source = Path.of("src/test/host-services/listener.sh");
        final String script =
                Files.readString(source)
                        .replace("9</root", "9<" + dir)
                        .replace("/var/tmp/porelo-host-services", dir.resolve("public").toString())
                        .replace("/root/porelo-host-services", dir.resolve("private").toString())
                        .replace(
                                "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
                                "PATH=" + bin + ":/usr/bin:/bin");
        final Path copy = Files.writeString(dir.resolve("listener.sh"), script);
        final List<String> command = new ArrayList<>(List.of("/bin/bash", copy.toString()));
        command.addAll(List.of(args));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.redirectOutput(dir.resolve("output").toFile());
        final Process process = builder.start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS))
                .as("script finishes without a listener")
                .isTrue();
        return process.exitValue();
    }

    private static void fake(Path bin, String name, String body) throws Exception {
        final Path file = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }
}
