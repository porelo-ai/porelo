package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests that nothing turns every check off at once: what may be relaxed has a narrow flag of its
 * own, and a switch or a setting that claimed to relax everything would silently do less than it
 * says, or more.
 */
class NoInsecureSwitchTest {

    private static final String PROJECT =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            """;

    @Test
    void anInsecureOptionIsRefusedAsUnknown() {
        final StringWriter err = new StringWriter();
        final CommandLine line = PoreloCli.commandLine(NoAccount.context());
        line.setErr(new PrintWriter(err, true));
        line.setOut(new PrintWriter(new StringWriter(), true));

        assertThat(line.execute("--insecure", "task", "list"))
                .as("refused with picocli's usage code, nothing run")
                .isEqualTo(2);
        assertThat(err.toString()).contains("--insecure");
        assertThat(line.execute("task", "start", "--insecure"))
                .as("nor on the start, where the checks are made")
                .isEqualTo(2);
    }

    @Test
    void anInsecureKeyInTheProjectFileIsRefusedAsUnknown() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(PROJECT + "insecure: true\n"), "p.yml"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'insecure' is not a setting");
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                PROJECT.replace(
                                                        "  security_class: \"guarded\"\n",
                                                        "  security_class: \"guarded\"\n"
                                                                + "  insecure: true\n")),
                                        "p.yml"))
                .as("nor inside the project's own block")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("insecure");
    }
}
