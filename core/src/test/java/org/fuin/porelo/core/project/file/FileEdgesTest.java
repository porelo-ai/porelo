package org.fuin.porelo.core.project.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectException;
import org.junit.jupiter.api.Test;

/** Files at the edge of what the reader and the writer take, each found by a reading. */
class FileEdgesTest {

    private static final String BASE =
            """
            project:
              name: shop
              security_class: guarded
            image:
              from: ubuntu:24.04
            """;

    private static ProjectFile read(final String yaml) {
        return ProjectFileReader.read(new StringReader(yaml), "project.yml", null);
    }

    private static List<String> faults(final String yaml) {
        final ProjectFileException refused =
                catchThrowableOfType(ProjectFileException.class, () -> read(yaml));
        assertThat(refused).as("the refusal of:\n" + yaml).isNotNull();
        return refused.faults();
    }

    @Test
    void aLongValueRepeatedByAliasesKeepsTheMessageSmall() {
        final String yaml =
                BASE
                        + "egress:\n  sets: [&x '!"
                        + "x".repeat(65_535)
                        + "'"
                        + ", *x".repeat(1000)
                        + "]\n";

        final List<String> faults = faults(yaml);

        assertThat(faults).hasSizeLessThanOrEqualTo(51);
        assertThat(String.join("\n", faults).length()).isLessThan(20_000);
        assertThat(faults.get(faults.size() - 1)).contains("more");
    }

    @Test
    void aQuotedHeredocMarkerHidesNoInstruction() {
        final String yaml =
                BASE
                        + """
                          run: |
                            RUN echo '<<EOF'
                            FROM alpine
                        """;

        assertThat(faults(yaml)).anyMatch(f -> f.contains("FROM is not allowed"));
    }

    @Test
    void aRealHeredocBodyIsStillSkipped() {
        final String yaml =
                BASE
                        + """
                          run: |
                            RUN <<'EOF'
                            apt-get update
                            EOF
                        """;

        assertThat(read(yaml).image().run()).contains("apt-get update");
    }

    @Test
    void aNumberIsReadAsYamlReadsIt() {
        assertThat(read(BASE + "limits:\n  pids: 010\n").limits().pids()).isEqualTo(8);
        assertThat(read(BASE + "limits:\n  pids: 0x10\n").limits().pids()).isEqualTo(16);
        assertThat(faults(BASE + "limits:\n  pids: 99999999999\n"))
                .anyMatch(f -> f.contains("too large"));
    }

    @Test
    void anAgentWrittenAsANumberIsRefused() {
        final String yaml = BASE + "repositories:\n  web:\n    agent: 123\n";

        assertThat(faults(yaml)).anyMatch(f -> f.contains("project.yml:8:") && f.contains("agent"));
    }

    @Test
    void aSetWrittenAsAYesIsRefused() {
        assertThat(faults(BASE + "egress:\n  sets: [on]\n"))
                .anyMatch(f -> f.contains("project.yml:7:") && f.contains("not a name"));
    }

    @Test
    void aNameWithADotIsNotTakenForTwoKeys() {
        final String yaml =
                BASE
                        + """
                        mail:
                          peers:
                            a:
                              address: 'matrix:'
                            a.address: {}
                        """;

        assertThat(faults(yaml))
                .anyMatch(
                        f ->
                                f.contains("project.yml:10:")
                                        && f.contains("mail.peers.\"a.address\".address"));
    }

    @Test
    void theWriterRefusesWhatCannotBeReadBack() {
        final RepositoryFile file =
                new RepositoryFile(
                        new Image(null, Map.of(), "RUN echo " + "x".repeat(3_145_728), null));

        assertThatThrownBy(() -> RepositoryFileWriter.write(file))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void aStartingBranchIsReadWrittenAndChecked() {
        final ProjectFile file = read(BASE + "base_branch: develop\n");

        assertThat(file.baseBranch()).isEqualTo("develop");
        assertThat(read(ProjectFileWriter.write(file))).isEqualTo(file);
        assertThat(faults(BASE + "base_branch: 'a..b'\n"))
                .anyMatch(f -> f.contains("project.yml:6:") && f.contains("base_branch"));
    }
}
