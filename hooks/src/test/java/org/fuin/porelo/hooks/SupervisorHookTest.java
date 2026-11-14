package org.fuin.porelo.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SupervisorHook}: the resolver that decides what a task's names resolve to is
 * started by a fixed path, not by whatever {@code PATH} finds first.
 */
class SupervisorHookTest {

    @Test
    void theResolverCommandUsesCheckedAbsolutePrograms() {
        assertThat(
                        SupervisorHook.resolverCommand(
                                4711, Path.of("/run/dns.conf"), name -> Path.of("/usr/sbin", name)))
                .as("PATH must not choose the program that enters the namespace, nor the resolver")
                .containsExactly(
                        "/usr/sbin/nsenter",
                        "--target",
                        "4711",
                        "--net",
                        "/usr/sbin/dnsmasq",
                        "--keep-in-foreground",
                        "--conf-file=/run/dns.conf");
    }

    @Test
    void anUnsafeResolverStartsNothingAndTheLogNamesThePath(@TempDir final Path dir)
            throws Exception {
        Files.writeString(dir.resolve("dns.conf"), "port=53\n");
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        dir.resolve("ruleset.nft").toString(),
                        dir.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        dir.toString())
                .writeTo(dir.resolve("sidecar.json"));
        Sidecar.recordId(dir, "abc123abc123");
        final String state =
                "{\"id\":\"abc123abc123\",\"pid\":4711,\"annotations\":{\""
                        + Sidecar.ANNOTATION
                        + "\":\""
                        + dir.resolve("sidecar.json")
                        + "\"}}";
        final SupervisorHook hook =
                new SupervisorHook(
                        name -> {
                            if ("nsenter".equals(name)) {
                                return Path.of("/usr/sbin", name);
                            }
                            final String reason =
                                    "Refusing /usr/sbin/" + name + ": not owned by root";
                            throw new java.io.UncheckedIOException(
                                    reason, new java.io.IOException(reason));
                        });

        final int exit =
                hook.execute(
                        new String[] {"createRuntime"},
                        new ByteArrayInputStream(state.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(exit).as("soft-fail: the container starts without its resolver").isZero();
        assertThat(dir.resolve(SupervisorHook.PID_FILE)).as("no resolver ran").doesNotExist();
        assertThat(Files.readString(dir.resolve("hooks.log")))
                .as("the operator needs the unsafe path and the reason")
                .contains("createRuntime failed: Refusing /usr/sbin/dnsmasq: not owned by root");
    }
}
