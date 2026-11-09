package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HostManPageTest {
    @CommandLine.Command(name = "future")
    static class FutureCommand {}

    @CommandLine.Command(name = "verb")
    static class FutureVerb {}

    private static List<String> missing(String manual, CommandLine command, String prefix) {
        final List<String> missing = new ArrayList<>();
        command.getSubcommands()
                .forEach(
                        (name, child) -> {
                            final String verb = prefix.isEmpty() ? name : prefix + " " + name;
                            if (!manual.replace("\\-", "-").contains(verb)) {
                                missing.add(verb);
                            }
                            missing.addAll(missing(manual, child, verb));
                        });
        return missing;
    }

    @Test
    void theManPageCoversEverySubcommandAndTheVersionOption() throws Exception {
        final String manual = Files.readString(Path.of("src/main/man/porelo-host.8"));
        assertThat(missing(manual, PoreloHost.commandLine(), ""))
                .as("every command in the parser must be described in section 8")
                .isEmpty();
        assertThat(manual.replace("\\-", "-"))
                .as("the frame's version option is documented even with zero verbs")
                .contains(
                        "--version",
                        ".TH PORELO-HOST 8",
                        "task start",
                        "task end",
                        "forge issue-token",
                        "forge lend-token");
    }

    @Test
    void anUndocumentedNestedVerbIsDetected() {
        final CommandLine command = new CommandLine(new FutureCommand());
        command.addSubcommand(
                "future",
                new CommandLine(new FutureCommand()).addSubcommand("verb", new FutureVerb()));
        assertThat(missing("future", command, ""))
                .as("the guard must fail when a later leaf verb is missing")
                .containsExactly("future verb");
    }

    @Test
    void theExitCodeSectionCoversEverySubcommand() throws Exception {
        final String manual = Files.readString(Path.of("src/main/man/porelo-host.8"));
        final String codes =
                manual.split("\\.SS Codes by verb\\n", 2)[1].split("\\.SH FILES", 2)[0];
        assertThat(missing(codes, PoreloHost.commandLine(), ""))
                .as("each verb documents its own codes, beyond its invocation description")
                .isEmpty();
    }

    @Test
    void aDocumentedTroffEscapedVerbIsRecognized() {
        final CommandLine command = new CommandLine(new FutureCommand());
        command.addSubcommand("future-verb", new FutureVerb());
        assertThat(missing(".B future\\-verb", command, ""))
                .as("troff hyphen escapes must not make a documented verb appear absent")
                .isEmpty();
    }

    @org.junit.jupiter.api.Test
    void theManualNamesEveryAcceptedEnvironmentName() throws Exception {
        final String manual =
                java.nio.file.Files.readString(java.nio.file.Path.of("src/main/man/porelo-host.8"));
        for (final String name : HostEnvironment.ALLOWED) {
            org.assertj.core.api.Assertions.assertThat(manual)
                    .as("the allow-list in the manual")
                    .containsPattern("(?m)(^| )" + java.util.regex.Pattern.quote(name) + "( |$)");
        }
    }
}
