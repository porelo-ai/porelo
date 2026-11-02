package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the line between what asks for the organization and how it is kept. Harm: a command that
 * reaches past the API into the Git adapter's state again, so that a second adapter could not stand
 * behind it; or an API type that says how the organization is kept, so that no other could.
 */
class OrganizationBoundaryTest {

    /** What only the Git adapter may name. */
    private static final Pattern ADAPTER_STATE =
            Pattern.compile(
                    "import org\\.fuin\\.porelo\\.organization\\.git\\."
                            + "(RegistryState|RegistryHistory|TrustRecords)\\b"
                            + "|org\\.fuin\\.porelo\\.organization\\.git\\."
                            + "(RegistryState|RegistryHistory|TrustRecords)\\.");

    /** What writes to the organization: it keeps its Git until its writes move. */
    private static final Set<String> WRITERS =
            Set.of("RegistryFileCommand.java", "IdentityBackupKeyCommand.java");

    /** Words that say how an organization is kept: a path, a commit, Git or a process. */
    private static final Pattern HOW_IT_IS_KEPT =
            Pattern.compile(
                    "\\bgit\\b|commit|\\bblob|java\\.nio\\.file|CommandRunner|\\bProcess\\b"
                            + "|org\\.fuin\\.porelo\\.(vault|core)",
                    Pattern.CASE_INSENSITIVE);

    private static Path root() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }

    private static List<Path> sources(final Path under) throws IOException {
        try (Stream<Path> files = Files.walk(under)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .toList();
        }
    }

    @Test
    void nothing_outside_the_git_adapter_names_its_state() throws IOException {
        final List<String> reaching = new ArrayList<>();
        for (final String area : List.of("apps", "daemon", "gate", "core", "vault", "supervisor")) {
            for (final Path source : sources(root().resolve(area))) {
                final String name = source.getFileName().toString();
                if (!WRITERS.contains(name)
                        && ADAPTER_STATE.matcher(Files.readString(source)).find()) {
                    reaching.add(root().relativize(source).toString());
                }
            }
        }

        assertThat(reaching).isEmpty();
    }

    @Test
    void the_api_says_nothing_of_how_the_organization_is_kept() throws IOException {
        final List<Path> types = sources(root().resolve("organization/api"));
        assertThat(types).as("the API's types are found").hasSizeGreaterThan(3);
        final List<String> naming = new ArrayList<>();
        for (final Path type : types) {
            final String code =
                    Files.readString(type)
                            .replaceAll("(?s)/\\*.*?\\*/", "")
                            .replaceAll("//[^\\n]*", "");
            if (HOW_IT_IS_KEPT.matcher(code).find()) {
                naming.add(root().relativize(type).toString());
            }
        }

        assertThat(naming).isEmpty();
    }

    @Test
    void the_check_finds_a_type_that_names_git() {
        assertThat(HOW_IT_IS_KEPT.matcher("record Revision(String commit) {}").find()).isTrue();
        assertThat(HOW_IT_IS_KEPT.matcher("import java.nio.file.Path;").find()).isTrue();
        assertThat(HOW_IT_IS_KEPT.matcher("record Person(String login) {}").find()).isFalse();
    }

    @Test
    void the_api_needs_wire_alone() throws IOException {
        final String pom = Files.readString(root().resolve("organization/api/pom.xml"));
        final String dependencies =
                pom.substring(pom.indexOf("<dependencies>"), pom.indexOf("</dependencies>"));

        assertThat(dependencies.split("<artifactId>", -1)).as("one dependency").hasSize(2);
        assertThat(dependencies).contains("<artifactId>porelo-wire</artifactId>");
    }
}
