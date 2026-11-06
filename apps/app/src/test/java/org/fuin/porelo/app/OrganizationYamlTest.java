package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.nodes.Node;

/** Guards against losing a person's comments or settings when an organization is settled. */
class OrganizationYamlTest {

    @TempDir private Path dir;

    @Test
    void settlingKeepsCommentsAndSettingsItDoesNotOwn() throws Exception {
        final PoreloContext context = context();
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                # My organization
                adapter: other # adapter note
                settings:
                  # Where it lives
                  repository: old # repository note
                  extra: [one, two] # extra note
                image:
                  setup: |
                    echo hello

                  # first group

                  # second group
                  mirror: example.org
                # Last note
                """);

        OrgAccess.settle(context, "git@forge.example:acme/organization.git");

        final String written = Files.readString(file);
        assertThat(written)
                .as("all of the person's comments survive")
                .contains(
                        "# My organization",
                        "# adapter note",
                        "# Where it lives",
                        "# repository note",
                        "# extra note",
                        "# first group",
                        "# second group",
                        "# Last note");
        assertThat(new Yaml().<Object>load(written))
                .as("only the owned keys change")
                .isEqualTo(
                        new Yaml()
                                .load(
                                        """
                        adapter: git
                        settings:
                          repository: git@forge.example:acme/organization.git
                          extra: [one, two]
                        image:
                          setup: |
                            echo hello
                          mirror: example.org
                        """));
        try (var entries = Files.list(file.getParent())) {
            assertThat(entries.toList())
                    .as("no temporary write is left behind")
                    .containsExactly(file);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "adapter: [broken",
                "- not a mapping",
                "settings: nope\n",
                "settings: {repository: one, repository: two}\n"
            })
    void anUnreadableFileIsNeverOverwritten(String original) throws Exception {
        final PoreloContext context = context();
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, original);

        assertThatThrownBy(() -> OrgAccess.settle(context, "git@forge.example:acme/o.git"))
                .as("an unreadable file must be refused rather than losing its contents")
                .isInstanceOf(Refused.class)
                .hasMessageContaining("organization.yml");
        assertThat(Files.readString(file))
                .as("a refused write leaves the original bytes")
                .isEqualTo(original);
    }

    @Test
    void separatedCommentGroupsAfterABlockScalarSurvive() {
        roundTrip(
                """
                a:
                  s: |
                    x

                  # c

                  # d
                  p: 1
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# Keep this note\n", "extra: kept\n", "settings: {extra: kept}\n"})
    void missingOwnedKeysAreAddedWithoutLosingWhatWasThere(String original) throws Exception {
        final PoreloContext context = context();
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, original);

        OrgAccess.settle(context, "git@forge.example:acme/o.git");

        assertThat(OrgAccess.repository(context))
                .as("the address is usable")
                .contains("git@forge.example:acme/o.git");
        if (original.contains("kept")) {
            assertThat(Files.readString(file))
                    .as("unknown settings survive")
                    .contains("extra: kept");
        }
        if (original.startsWith("#")) {
            assertThat(Files.readString(file))
                    .as("a comment-only file keeps its note")
                    .contains("# Keep this note");
        }
    }

    @Test
    void theBuildFileKeepsItsComments() throws Exception {
        roundTrip(Files.readString(Path.of("src/test/resources/yaml/build.yml")));
    }

    @Test
    void settlingAnAliasDoesNotChangeAnUnownedMapping() throws Exception {
        final PoreloContext context = context();
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "extra: &shared {repository: old}\nsettings: *shared\n");

        OrgAccess.settle(context, "git@forge.example:acme/o.git");

        assertThat(new Yaml().<Object>load(Files.readString(file)))
                .as("an alias must not let an owned edit change somebody else's settings")
                .isEqualTo(
                        new Yaml()
                                .load(
                                        "extra: {repository: old}\nsettings: {repository: git@forge.example:acme/o.git}\nadapter: git\n"));
    }

    @Test
    void trailingSeparatedCommentGroupsSurvive() {
        roundTrip("a:\n  s: |\n    x\n\n  # end1\n\n  # end2\n");
    }

    @Test
    void commentsSnakeYamlCannotKeepCauseNoWrite() throws Exception {
        final PoreloContext context = context();
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        final String original = "a: &x abc\nb: *x # alias note\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, original);

        assertThatThrownBy(() -> OrgAccess.settle(context, "git@forge.example:acme/o.git"))
                .as("comments on aliases must never be silently discarded")
                .isInstanceOf(Refused.class)
                .hasMessageContaining("comments");
        assertThat(Files.readString(file))
                .as("the original comments stay safe")
                .isEqualTo(original);
    }

    @Test
    void plainSnakeYamlStillNeedsTheWorkaround() throws Exception {
        final LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        final String build = Files.readString(Path.of("src/test/resources/yaml/build.yml"));
        assertThat(catchThrowable(() -> new Yaml(options).compose(new StringReader(build))))
                .as("Plain SnakeYAML now composes build.yml with comments: the workaround can go")
                .isInstanceOf(ClassCastException.class)
                .hasMessageContaining("CommentEvent");
    }

    @Test
    @Tag("documents")
    void validDocumentedYamlKeepsItsComments() throws Exception {
        final String page = Files.readString(Path.of("..", "..", "doc", "project-file.md"));
        final var blocks = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL).matcher(page);
        int valid = 0;
        while (blocks.find()) {
            final String example = blocks.group(1);
            try {
                new Yaml().load(example);
            } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
                continue;
            }
            roundTrip(example);
            valid++;
        }
        assertThat(valid)
                .as(
                        "the page's complete project, repository image and repository settings examples")
                .isEqualTo(3);
    }

    private static void roundTrip(String text) {
        final Node node = TolerantComposer.compose(text);
        final DumperOptions output = new DumperOptions();
        output.setProcessComments(true);
        final StringWriter written = new StringWriter();
        new Yaml(output).serialize(node, written);
        assertThat(new Yaml().<Object>load(written.toString()))
                .as("serialization preserves YAML values")
                .isEqualTo(new Yaml().load(text));
        assertThat(comments(written.toString()))
                .as("every whole-line and inline comment survives in order")
                .containsExactlyElementsOf(comments(text));
    }

    private static List<String> comments(String text) {
        final LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        final List<String> comments = new ArrayList<>();
        for (Event event : new Yaml(options).parse(new StringReader(text))) {
            if (event instanceof CommentEvent comment
                    && comment.getCommentType() != CommentType.BLANK_LINE) {
                comments.add(comment.getValue());
            }
        }
        return comments;
    }

    private PoreloContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new PoreloContext(
                new ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0,
                name -> null);
    }
}
