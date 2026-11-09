package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectRuntimeTest {
    private ProjectRuntime accounts(int status, String output) {
        return runtime(
                (args, input, environment, timeout) -> {
                    assertThat(args)
                            .as("NSS is queried for the fixed configuration account alone")
                            .containsExactly("/usr/bin/getent", "passwd", "forge");
                    return new HostPrograms.Result(status, output);
                },
                Path::of);
    }

    private ProjectRuntime runtime(
            ProjectRuntime.Commands commands, ProjectRuntime.Programs programs) {
        return new ProjectRuntime(
                new HostFiles(),
                commands,
                programs,
                (socket, token) -> {
                    throw new AssertionError("No forge request expected");
                });
    }

    @Test
    void anAbsentAccountIsDistinctFromAnUnavailableAccountDatabase() {
        assertThat(accounts(2, "").find("forge"))
                .as("getent status 2 means the account does not exist")
                .isNull();
        assertThatThrownBy(() -> accounts(2, "").required("forge"))
                .as("a configured service account is required")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("a required service or person account is missing");
        assertThatThrownBy(() -> accounts(3, "").find("forge"))
                .as("an NSS failure is never an absent person")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("a required service or person account is missing");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "other:x:900:900::/nonexistent:/bin/sh\n",
                "forge:x:bad:900::/nonexistent:/bin/sh\n",
                "forge:x:4294967295:900::/nonexistent:/bin/sh\n",
                "forge:x:900:900::/nonexistent:/bin/sh\njunk\n",
                "forge:x:900:900::/nonexistent:/bin/sh\nforge:x:900:900::/other:/bin/sh\n"
            })
    void anAmbiguousOrInvalidPasswdAnswerIsRefused(String passwd) {
        assertThatThrownBy(() -> accounts(0, passwd).required("forge"))
                .as("an account lookup must return one matching numeric account")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("a required service or person account is missing");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "forge:x:0:900::/nonexistent:/bin/sh\n",
                "forge:x:900:900::relative:/bin/sh\n",
                "forge:x:900:900::/home/../other:/bin/sh\n",
                "forge:x:900:900::/home//forge:/bin/sh\n",
                "forge:x:900:900::/home/forge/:/bin/sh\n"
            })
    void rootAndNoncanonicalHomesCannotReachRunuser(String passwd) {
        assertThatThrownBy(() -> accounts(0, passwd).required("forge"))
                .as("the host refuses root and unusable homes before account switching")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("a required account is root or has no usable absolute home");
    }

    @Test
    void aProgramFailureNeverRelaysItsOutputOrArguments() {
        final ProjectRuntime commands =
                runtime(
                        (args, input, environment, timeout) -> {
                            throw new IOException("private response and credential");
                        },
                        Path::of);
        assertThatThrownBy(() -> commands.command(List.of("runuser", "private argument")))
                .as("exceptions cannot reveal command data")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("runuser failed to complete (OSError)");
    }

    @Test
    void theServiceAccountCommandUsesItsOwnRuntimeAndHome() {
        assertThat(
                        accounts(0, "forge:x:900:900::/nonexistent:/bin/sh\n")
                                .required("forge")
                                .switched(
                                        Path.of("runuser"),
                                        Path.of("env"),
                                        List.of("podman", "exec", "forgejo")))
                .as(
                        "the host's fixed environment switches into the account without inheriting root's home")
                .containsExactly(
                        "runuser",
                        "-u",
                        "forge",
                        "--",
                        "env",
                        "-C",
                        "/",
                        "HOME=/nonexistent",
                        "XDG_RUNTIME_DIR=/run/user/900",
                        "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/900/bus",
                        "podman",
                        "exec",
                        "forgejo");
    }

    @Test
    void anAbsentOptionalProgramIsDistinctFromAnUnsafeOne() {
        final ProjectRuntime absent =
                runtime(
                        (argv, input, environment, timeout) -> {
                            throw new AssertionError("No command expected");
                        },
                        name -> {
                            throw new java.io.UncheckedIOException(
                                    new org.fuin.porelo.wire.SystemProgram.Missing(
                                            name, List.of()));
                        });
        final ProjectRuntime unsafe =
                runtime(
                        (argv, input, environment, timeout) -> {
                            throw new AssertionError("No command expected");
                        },
                        name -> {
                            throw new java.io.UncheckedIOException(
                                    new IOException("Refusing unsafe path"));
                        });
        assertThat(absent.available("porelo"))
                .as("a missing optional task stopper may be skipped")
                .isFalse();
        assertThatThrownBy(() -> unsafe.available("porelo"))
                .as("an unsafe installed binary cannot become optional absence")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("cannot inspect required program porelo");
    }
}
