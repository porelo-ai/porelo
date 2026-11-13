package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.RepositoryImageReader;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ToolsDraft}: the tools of a proposal written as a {@code .porelo.yml} where its
 * reader finds it, and read back before it is kept.
 */
class ToolsDraftTest {

    private static final String JAVA = "RUN echo java\n";

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @TempDir Path dir;

    private PoreloContext context;

    @BeforeEach
    void machine() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private static Finding finding(final String key, final String value, final String file) {
        return new Finding(
                Finding.Part.TOOLS, key, value, file, 3, Finding.Origin.CHECKOUT, false, "");
    }

    /** A proposal with a package, an adapter's lines and a variable, as the adapters give them. */
    private static ToolsDraft proposed() {
        final ProjectProposal made = new ProjectProposal("shop", null, null, null);
        made.add(
                "maven",
                new Detection(
                        List.of(
                                finding("package.git", "", "Dockerfile"),
                                finding("run.java", JAVA, ".tool-versions"),
                                finding("env.JAVA_HOME", "/opt/java", "pom.xml"),
                                finding("maven.version", "3.9.9", "pom.xml")),
                        List.of()));
        made.answer();
        return ToolsDraft.of(made);
    }

    @Test
    void aWrittenDraftReadBackGivesTheSameTools() {
        final ToolsDraft draft = proposed();

        final ImageSource read =
                RepositoryImageReader.read(draft.text(), ".porelo.yml", (named, origin) -> "");

        assertThat(draft.isEmpty()).isFalse();
        assertThat(read.baseImage()).as("the project's base image stands").isNull();
        assertThat(read.snippet())
                .isEqualTo(
                        "RUN apt-get update && apt-get install -y --no-install-recommends git"
                                + " && rm -rf /var/lib/apt/lists/*\n"
                                + JAVA);
        assertThat(read.env()).containsExactly(Map.entry("JAVA_HOME", "/opt/java"));
        assertThat(draft.text())
                .as("each line and variable named with the file it was read from, as a comment")
                .contains(
                        "# RUN echo java  <- .tool-versions", "# JAVA_HOME=/opt/java  <- pom.xml");
    }

    @Test
    void aDraftThatDoesNotReadBackTheSameToolsIsRefused() {
        final ToolsDraft draft = proposed();

        assertThatThrownBy(
                        () ->
                                ToolsDraft.readBack(
                                        draft.text(),
                                        new Image(
                                                null,
                                                Map.of("JAVA_HOME", "/elsewhere"),
                                                draft.image().run(),
                                                null)))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("read back");
        assertThatThrownBy(
                        () ->
                                ToolsDraft.readBack(
                                        draft.text() + "egress:\n  domains:\n    - \"x.example\"\n",
                                        draft.image()))
                .as("nothing but the image is a tools draft")
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void aCommentCannotCarryALineOfItsOwn() {
        final ProjectProposal made = new ProjectProposal("shop", null, null, null);
        made.add(
                "maven",
                new Detection(
                        List.of(finding("run.java", JAVA, "pom.xml\negress:\n  domains: [x]")),
                        List.of()));
        made.answer();

        final String text = ToolsDraft.of(made).text();

        assertThat(text.lines().filter(line -> !line.startsWith("#") && !line.startsWith(" ")))
                .as("a source's line break stays inside its comment")
                .containsExactly("image:");
    }

    @Test
    void inACheckoutItIsWrittenAndExcludedAndTrackedFilesStayUnchanged() throws Exception {
        final Path checkout = checkout();
        final DefaultProject.Entry entry =
                new DefaultProject(context).add(checkout.toString(), "shop", checkout.toString());
        assertThat(entry.source()).isEqualTo(DefaultProject.Source.CHECKOUT);

        final Path written = proposed().write(context, entry);

        assertThat(written).isEqualTo(checkout.resolve(".porelo.yml"));
        assertThat(
                        RepositoryImageReader.readBesideSettings(
                                        Files.readString(written),
                                        written.toString(),
                                        (named, origin) -> "")
                                .env())
                .containsEntry("JAVA_HOME", "/opt/java");
        assertThat(git(checkout, "status", "--porcelain", "--untracked-files=all"))
                .as("the draft is excluded and no tracked file changed")
                .isEmpty();
        assertThat(Files.readString(checkout.resolve("pom.xml"))).isEqualTo("<project/>\n");

        Files.delete(written);
        proposed().write(context, entry);
        assertThat(excluded(checkout))
                .as("the line is added once, not twice")
                .containsOnlyOnce("/.porelo.yml");
    }

    @Test
    void anExistingFileInACheckoutIsNeverOverwritten() throws Exception {
        final Path checkout = checkout();
        final DefaultProject.Entry entry =
                new DefaultProject(context).add(checkout.toString(), "shop", checkout.toString());
        Files.writeString(checkout.resolve(".porelo.yml"), "image:\n  from: \"mine\"\n");
        final String exclude = excluded(checkout);

        assertThatThrownBy(() -> proposed().write(context, entry))
                .isInstanceOf(ToolsDraft.Exists.class)
                .hasMessageContaining(".porelo.yml");

        assertThat(Files.readString(checkout.resolve(".porelo.yml")))
                .isEqualTo("image:\n  from: \"mine\"\n");
        assertThat(excluded(checkout)).as("nothing else is touched").isEqualTo(exclude);
    }

    @Test
    void aWorktreesDraftIsExcludedWhereGitReadsItsExcludes() throws Exception {
        final Path checkout = checkout();
        final Path worktree = dir.resolve("shop-wt");
        git(checkout, "worktree", "add", "-q", worktree.toString());
        final DefaultProject.Entry entry =
                new DefaultProject(context).add(worktree.toString(), "shop", worktree.toString());

        proposed().write(context, entry);

        assertThat(git(worktree, "status", "--porcelain", "--untracked-files=all")).isEmpty();
    }

    @Test
    void forARemoteEntryItIsWrittenUnderTheConfigurationDirectoryForThePersonAlone()
            throws Exception {
        final DefaultProject.Entry entry =
                new DefaultProject(context).add("https://forge.example/acme/far.git", "far", "");
        assertThat(entry.source()).isEqualTo(DefaultProject.Source.REMOTE);

        final Path written = proposed().write(context, entry);

        final Path expected = dir.resolve("config/porelo/projects/default/far/.porelo.yml");
        assertThat(written).isEqualTo(expected);
        assertThat(ToolsDraft.remote(context, "far")).isEqualTo(expected);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written)))
                .isEqualTo("rw-------");
        for (final Path each : List.of(written.getParent(), written.getParent().getParent())) {
            assertThat(
                            PosixFilePermissions.toString(
                                    Files.getPosixFilePermissions(each, LinkOption.NOFOLLOW_LINKS)))
                    .as(each.toString())
                    .isEqualTo("rwx------");
        }
        assertThat(new DefaultProject(context).proposal(entry))
                .as("a repository with its draft has settings of its own")
                .isEqualTo(DefaultProject.Proposal.CONFIGURED);
        assertThatThrownBy(() -> proposed().write(context, entry))
                .isInstanceOf(ToolsDraft.Exists.class);
    }

    @Test
    void anEmptyDraftWritesNothing() throws Exception {
        final ProjectProposal made = new ProjectProposal("shop", null, null, null);
        made.add(
                "maven",
                new Detection(List.of(finding("maven.version", "3.9.9", "pom.xml")), List.of()));
        made.answer();
        final DefaultProject.Entry entry =
                new DefaultProject(context).add("https://forge.example/acme/far.git", "far", "");

        final ToolsDraft draft = ToolsDraft.of(made);

        assertThat(draft.isEmpty()).isTrue();
        assertThat(draft.write(context, entry)).isNull();
        assertThat(ToolsDraft.remote(context, "far")).doesNotExist();
    }

    private Path checkout() throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("shop"));
        git(checkout, "init", "-q");
        Files.writeString(checkout.resolve("pom.xml"), "<project/>\n");
        git(checkout, "add", "pom.xml");
        git(checkout, "commit", "-q", "-m", "first");
        return checkout.toRealPath();
    }

    private static String excluded(final Path checkout) throws Exception {
        final Path exclude = checkout.resolve(".git/info/exclude");
        return Files.isRegularFile(exclude)
                ? Files.readString(exclude, StandardCharsets.UTF_8)
                : "";
    }

    private String git(final Path directory, final String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "init.defaultBranch=main"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(
                        new Command(
                                all,
                                directory,
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1"),
                                null))
                .standardOutput();
    }
}
