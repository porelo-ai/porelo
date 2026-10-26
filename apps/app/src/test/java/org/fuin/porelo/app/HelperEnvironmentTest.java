package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HelperEnvironment}: what of the caller's environment reaches the filter and a
 * transport.
 */
class HelperEnvironmentTest {

    @Test
    void onlyWhatAProcessNeedsPassesAndNothingThatSteersAHelper() {
        final Map<String, String> caller =
                Map.of(
                        "PATH",
                        "/usr/bin",
                        "HOME",
                        "/home/a",
                        "XDG_RUNTIME_DIR",
                        "/run/user/1000",
                        "PORELO_MSGSLUICE_DECISION_DISABLEDRULES",
                        "ENCODING/BASE64",
                        "PORELO_MATRIX_TLS_VERIFY",
                        "off",
                        "CLAUDECODE",
                        "1",
                        "CLAUDE_CODE_SESSION_ID",
                        "s-1");

        assertThat(HelperEnvironment.base(caller))
                .as("what a process needs, and a UTF-8 locale where the caller had none")
                .containsOnlyKeys("PATH", "HOME", "XDG_RUNTIME_DIR", "LANG");
    }

    @Test
    void aCallerWithNoLocaleHandsAHelperCUtf8() {
        // In the C locale Java reads a program's arguments and file names as ASCII: a transport
        // enrolled with a name holding an umlaut came back with a question mark.
        assertThat(HelperEnvironment.base(Map.of("PATH", "/usr/bin", "LANG", "")))
                .as("an empty LANG is no locale")
                .containsEntry("LANG", "C.UTF-8");
    }

    @Test
    void aLocaleTheCallerSetsPassesUnchanged() {
        assertThat(HelperEnvironment.base(Map.of("LANG", "C")))
                .as("a LANG the machine set, even C, is the machine's choice")
                .containsExactlyEntriesOf(Map.of("LANG", "C"));
        assertThat(HelperEnvironment.base(Map.of("LC_ALL", "de_DE.UTF-8")))
                .as("LC_ALL decides, so no LANG is added beside it")
                .containsExactlyEntriesOf(Map.of("LC_ALL", "de_DE.UTF-8"));
        assertThat(HelperEnvironment.base(Map.of("LC_CTYPE", "en_US.UTF-8")))
                .as("LC_CTYPE decides how bytes are read, so no LANG is added beside it")
                .containsExactlyEntriesOf(Map.of("LC_CTYPE", "en_US.UTF-8"));
    }

    @Test
    void aChosenEnvironmentReallyStartsTheProcessWithoutTheCallersOwn() {
        // Through the real runner: 'env' prints what the child got.
        final String printed =
                new ProcessCommandRunner(java.time.Duration.ofSeconds(10))
                        .runOrFail(
                                Command.of("env")
                                        .withEnvironment(Map.of("HANDED", "yes"))
                                        .withChosenEnvironment(
                                                Map.of(
                                                        "PATH",
                                                        System.getenv()
                                                                .getOrDefault("PATH", "/usr/bin"))))
                        .standardOutput();

        assertThat(printed).contains("HANDED=yes").contains("PATH=");
        assertThat(
                        printed.lines()
                                .filter(
                                        line ->
                                                line.startsWith("HOME=")
                                                        || line.startsWith("USER=")))
                .isEmpty();
    }
}
