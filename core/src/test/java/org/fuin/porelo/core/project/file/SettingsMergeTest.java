package org.fuin.porelo.core.project.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectException;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

class SettingsMergeTest {

    private static final String PROJECT =
            """
            image:
              from: "ubuntu:24.04"
              env:
                JAVA_HOME: "/opt/java"
            egress:
              domains:
                - "repo.example.org"
                - "mirror.example.org"
            limits:
              memory: "4g"
              cpus: 2
            """;

    @Test
    void mergesMappingsKeyByKeyAndAValueTheRepositorySetsWins() {
        final SettingsMerge.Merged merged =
                merge(
                        """
                        image:
                          env:
                            MAVEN_OPTS: "-Xmx1g"
                        limits:
                          memory: "8g"
                        """);

        assertThat(plain(merged))
                .isEqualTo(
                        Map.of(
                                "image",
                                Map.of(
                                        "from",
                                        "ubuntu:24.04",
                                        "env",
                                        Map.of("JAVA_HOME", "/opt/java", "MAVEN_OPTS", "-Xmx1g")),
                                "egress",
                                Map.of(
                                        "domains",
                                        List.of("repo.example.org", "mirror.example.org")),
                                "limits",
                                Map.of("memory", "8g", "cpus", 2)));
        assertThat(merged.sources())
                .containsEntry("image.from", "the project")
                .containsEntry("image.env.MAVEN_OPTS", "porelo.yml of shop")
                .containsEntry("limits.memory", "porelo.yml of shop")
                .containsEntry("limits.cpus", "the project");
    }

    @Test
    void aListTakesTheProjectsEntriesThenTheRepositorys() {
        final SettingsMerge.Merged merged =
                merge(
                        """
                        egress:
                          domains:
                            - "build.example.org"
                        """);

        assertThat(plain(merged))
                .extracting(map -> ((Map<?, ?>) map.get("egress")).get("domains"))
                .isEqualTo(List.of("repo.example.org", "mirror.example.org", "build.example.org"));
        assertThat(merged.sources())
                .containsEntry("egress.domains[repo.example.org]", "the project")
                .containsEntry("egress.domains[build.example.org]", "porelo.yml of shop");
    }

    @Test
    void nullRemovesAKeyTheProjectSet() {
        final SettingsMerge.Merged merged =
                merge(
                        """
                        image:
                          env:
                            JAVA_HOME: ~
                        limits:
                          cpus: null
                        """);

        assertThat(plain(merged))
                .extracting(map -> map.get("limits"))
                .isEqualTo(Map.of("memory", "4g"));
        assertThat(plain(merged))
                .extracting(map -> map.get("image"))
                .isEqualTo(Map.of("from", "ubuntu:24.04", "env", Map.of()));
        assertThat(merged.removed())
                .containsExactly(
                        new SettingsMerge.Removed("image.env.JAVA_HOME", "porelo.yml of shop"),
                        new SettingsMerge.Removed("limits.cpus", "porelo.yml of shop"));
    }

    @Test
    void aQuotedBangRemovesThatInheritedEntryAndBangStarEveryOne() {
        final SettingsMerge.Merged one =
                merge(
                        """
                        egress:
                          domains:
                            - "!mirror.example.org"
                        """);
        final SettingsMerge.Merged all =
                merge(
                        """
                        egress:
                          domains:
                            - '!*'
                            - "build.example.org"
                        """);

        assertThat(plain(one))
                .extracting(map -> ((Map<?, ?>) map.get("egress")).get("domains"))
                .isEqualTo(List.of("repo.example.org"));
        assertThat(one.removed())
                .containsExactly(
                        new SettingsMerge.Removed(
                                "egress.domains[mirror.example.org]", "porelo.yml of shop"));
        assertThat(plain(all))
                .extracting(map -> ((Map<?, ?>) map.get("egress")).get("domains"))
                .as("only the repository's own entry is left")
                .isEqualTo(List.of("build.example.org"));
        assertThat(all.removed()).hasSize(2);
    }

    @Test
    void refusesAnUnquotedBangNamingTheQuotes() {
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        egress:
                                          domains:
                                            - !mirror.example.org
                                        """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo.yml of shop")
                .hasMessageContaining("line 3")
                .hasMessageContaining("quotes");
    }

    @Test
    void refusesARemovalThatMatchesNothingNamingIt() {
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        egress:
                                          domains:
                                            - "!nowhere.example.org"
                                        """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("nowhere.example.org")
                .hasMessageContaining("line 3");
    }

    @Test
    void aLaterLayerMergesOnTheEarlierOnes() {
        final SettingsMerge.Merged merged =
                SettingsMerge.merge(
                        List.of(
                                layer("the project", PROJECT),
                                layer(
                                        "porelo.yml of shop",
                                        """
                                        limits:
                                          memory: "8g"
                                        """),
                                layer(
                                        "the local override",
                                        """
                                        limits:
                                          memory: "16g"
                                        """)));

        assertThat(merged.sources()).containsEntry("limits.memory", "the local override");
        assertThat(plain(merged))
                .extracting(map -> ((Map<?, ?>) map.get("limits")).get("memory"))
                .isEqualTo("16g");
    }

    @Test
    void judgesRemovalsUnderAKeyTheProjectNeverSet() {

        // A list or mapping new in the repository's file was taken as written: "!*" stayed an
        // entry, a removal matching nothing passed, and a null key stayed in the tree.
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        build:
                                          package_sources:
                                            - "!nowhere.example.org"
                                        """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("nowhere.example.org");
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        build:
                                          package_sources:
                                            - !nowhere.example.org
                                        """))
                .hasMessageContaining("quotes");
        final SettingsMerge.Merged merged =
                merge(
                        """
                        build:
                          package_sources:
                            - '!*'
                            - "maven"
                          other: ~
                        """);
        assertThat(plain(merged))
                .extracting(map -> map.get("build"))
                .isEqualTo(Map.of("package_sources", List.of("maven")));
        assertThat(merged.sources()).doesNotContainKey("build.other");
    }

    @Test
    void refusesAKeyWrittenTwiceInEitherFile() {

        // compose keeps both entries; merged into one, the binder never saw the second.
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        limits:
                                          memory: "2g"
                                          memory: "16g"
                                        """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.memory")
                .hasMessageContaining("twice");
    }

    @Test
    void refusesNullInAListAnAliasAndARootThatIsNoMapping() {
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        egress:
                                          domains:
                                            - ~
                                        """))
                .hasMessageContaining("egress.domains");
        assertThatThrownBy(
                        () ->
                                merge(
                                        """
                                        image:
                                          env: &a
                                            SELF: *a
                                        """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("alias");
        assertThatThrownBy(() -> merge("- \"item\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void anEntryTheSameLayerAddsSurvivesItsOwnRemovalOfTheInheritedOne() {
        final SettingsMerge.Merged merged =
                merge(
                        """
                        egress:
                          domains:
                            - "repo.example.org"
                            - "!*"
                        """);

        assertThat(plain(merged))
                .extracting(map -> ((Map<?, ?>) map.get("egress")).get("domains"))
                .isEqualTo(List.of("repo.example.org"));
        assertThat(merged.sources())
                .containsEntry("egress.domains[repo.example.org]", "porelo.yml of shop");
        assertThat(merged.removed())
                .as("what is there again is not listed as gone")
                .extracting(SettingsMerge.Removed::path)
                .containsExactly("egress.domains[mirror.example.org]");
    }

    @Test
    void removingEveryMappingEntryOfAListForgetsEachOne() {
        final SettingsMerge.Merged merged =
                SettingsMerge.merge(
                        List.of(
                                layer(
                                        "the project",
                                        """
                                        items:
                                          - a: "1"
                                          - b: "2"
                                        """),
                                layer(
                                        "porelo.yml of shop",
                                        """
                                        items:
                                          - '!*'
                                        """)));

        assertThat(merged.sources()).isEmpty();
        assertThat(merged.removed())
                .extracting(SettingsMerge.Removed::path)
                .containsExactly("items[0]", "items[1]");
    }

    private static SettingsMerge.Merged merge(final String repository) {
        return SettingsMerge.merge(
                List.of(layer("the project", PROJECT), layer("porelo.yml of shop", repository)));
    }

    private static SettingsMerge.Layer layer(final String source, final String text) {
        return new SettingsMerge.Layer(source, Binding.compose(new StringReader(text), source));
    }

    /** The merged tree as plain values, as a file written from it would read. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> plain(final SettingsMerge.Merged merged) {
        final Node root = merged.root();
        final Yaml yaml = new Yaml();
        final String text = yaml.serialize(root) == null ? "" : dump(root);
        return (Map<String, Object>) yaml.load(text);
    }

    private static String dump(final Node root) {
        final java.io.StringWriter out = new java.io.StringWriter();
        new Yaml().serialize(root, out);
        return out.toString();
    }
}
