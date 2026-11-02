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
                    "org\\.fuin\\.porelo\\.organization\\.gittree\\."
                            + "(TreeModel|TreeWalk|TreeFiles|TreeRecord|TreeRepository)\\b"
                            + "|import org\\.fuin\\.porelo\\.organization\\.gittree\\.\\*"
                            + "|\\bwalked\\(|\\.walk\\(opened|store\\(\\)\\.walk\\(");

    /** Every area with sources of its own, apart from the Git adapter. */
    private static final List<String> AREAS_OUTSIDE_THE_ADAPTER =
            List.of(
                    "agents",
                    "apps",
                    "builds",
                    "clearance",
                    "core",
                    "daemon",
                    "egress",
                    "gate",
                    "hooks",
                    "organization/api",
                    "providers",
                    "runtime",
                    "shield",
                    "supervisor",
                    "vault");

    /** A host's forge port, which a host's card names: of a host's reach, not of how it is kept. */
    private static final String FORGE_PORT = "forgeGitPort";

    /** What may name the adapter's state outside it: nothing. */
    private static final Set<String> WRITERS = Set.of();

    /** Words that say how an organization is kept: a path, a commit, Git or a process. */
    private static final Pattern HOW_IT_IS_KEPT =
            Pattern.compile(
                    "git|commit|\\bblob|java\\.nio\\.file|java\\.io\\.File|java\\.util\\.function"
                            + "|CommandRunner|\\bProcess\\b|org\\.fuin\\.porelo\\.(vault|core)",
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
        final List<Path> outside = new ArrayList<>();
        for (final String area : AREAS_OUTSIDE_THE_ADAPTER) {
            outside.addAll(sources(root().resolve(area)));
        }
        assertThat(outside).as("sources are found").hasSizeGreaterThan(500);
        for (final Path source : outside) {
            final String name = source.getFileName().toString();
            if (!WRITERS.contains(name) && ADAPTER_STATE.matcher(Files.readString(source)).find()) {
                reaching.add(root().relativize(source).toString());
            }
        }

        assertThat(reaching).isEmpty();
    }

    @Test
    void the_check_finds_a_name_of_the_adapters_state() {
        assertThat(
                        ADAPTER_STATE
                                .matcher("new org.fuin.porelo.organization.gittree.TreeWalk(r)")
                                .find())
                .isTrue();
        assertThat(ADAPTER_STATE.matcher("import org.fuin.porelo.organization.gittree.*;").find())
                .isTrue();
        assertThat(ADAPTER_STATE.matcher("var state = RegistryFileCommand.walked(c);").find())
                .isTrue();
        assertThat(ADAPTER_STATE.matcher("store.read(identities)").find()).isFalse();
    }

    /** Git started by the code around the organization, which only the adapter may do. */
    private static final Pattern RUNS_GIT =
            Pattern.compile("(Command\\.of|List\\.of)\\(\\s*\"git\"" + "|\\bTreeRepository\\b");

    /**
     * Harm guarded: a commit or a fetch of the organization's repository that the adapter's sealed
     * git and its checks never saw. The code asking the organization runs no git of its own.
     */
    @Test
    void no_class_around_the_organization_runs_git_on_it() throws IOException {
        final List<String> running = new ArrayList<>();
        for (final String area : List.of("apps/app-vault", "apps/app-organization")) {
            for (final Path source : sources(root().resolve(area))) {
                if (RUNS_GIT.matcher(Files.readString(source)).find()) {
                    running.add(root().relativize(source).toString());
                }
            }
        }

        assertThat(running).isEmpty();
    }

    @Test
    void the_check_finds_a_class_that_runs_git() {
        assertThat(RUNS_GIT.matcher("Command.of(\"git\", \"fetch\")").find()).isTrue();
        assertThat(RUNS_GIT.matcher("List.of(\n    \"git\",\n \"push\")").find()).isTrue();
        assertThat(RUNS_GIT.matcher("new TreeRepository(runner)").find()).isTrue();
        assertThat(RUNS_GIT.matcher("Command.of(\"ssh-keygen\")").find()).isFalse();
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
                            .replaceAll("//[^\\n]*", "")
                            .replace(FORGE_PORT, "");
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
        assertThat(HOW_IT_IS_KEPT.matcher("record Revision(String gitRef) {}").find()).isTrue();
        assertThat(HOW_IT_IS_KEPT.matcher("import java.io.File;").find()).isTrue();
        assertThat(HOW_IT_IS_KEPT.matcher("import java.util.function.Supplier;").find()).isTrue();
        assertThat(HOW_IT_IS_KEPT.matcher("record Person(String login) {}").find()).isFalse();
    }

    @Test
    void the_organization_is_answered_by_question_not_as_one_whole_read() {
        assertThat(org.fuin.porelo.organization.Organization.class.isInterface())
                .as("each adapter answers each question itself")
                .isTrue();
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
