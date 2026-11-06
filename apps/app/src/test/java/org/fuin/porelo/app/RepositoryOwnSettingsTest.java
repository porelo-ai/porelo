package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RepositoryOwnSettings} and the start check that answers with it: what a
 * repository's own files in 'default' ask of this machine, allowed once and asked again when it
 * changes.
 */
class RepositoryOwnSettingsTest {

    private static final StartCheck.Result READY =
            new StartCheck.Result(StartCheck.Outcome.READY, "an-agent", "", "", "");

    @TempDir Path dir;

    @Test
    void aSettingTheRepositoryAsksForIsRefusedUntilAllowed() throws Exception {
        final PoreloContext context = context();
        final Path projectFile =
                shop(context, "egress:\n  domains:\n    - \"build.example.org\"\n");

        final StartCheck.Result refused =
                StartCheck.withSettings(context, READY, projectFile, "shop");

        assertThat(refused.outcome()).isEqualTo(StartCheck.Outcome.SETTINGS_NOT_ALLOWED);
        assertThat(refused.agent()).as("what was settled stays").isEqualTo("an-agent");
        assertThat(refused.detail())
                .contains("porelo.yml of shop")
                .contains("+ egress.domains[build.example.org] = build.example.org");
        assertThat(refused.settingsToAllow())
                .containsExactly(
                        Map.of(
                                "key",
                                "egress.domains[build.example.org]",
                                "value",
                                "build.example.org",
                                "was",
                                "",
                                "removed",
                                false));
        assertThat(refused.settingsHash()).hasSize(64);
        assertThat(refused.asMap()).containsEntry("settingsHash", refused.settingsHash());

        assertThat(RepositoryOwnSettings.allowIfUnchanged(context, "shop", "0".repeat(64)))
                .as("a hash nobody was shown allows nothing")
                .isFalse();
        assertThat(RepositoryOwnSettings.allowIfUnchanged(context, "shop", refused.settingsHash()))
                .isTrue();
        assertThat(StartCheck.withSettings(context, READY, projectFile, "shop").outcome())
                .isEqualTo(StartCheck.Outcome.READY);
    }

    @Test
    void aChangedFileAsksAgainForWhatChangedAlone() throws Exception {
        final PoreloContext context = context();
        final Path projectFile =
                shop(
                        context,
                        "egress:\n  domains:\n    - \"build.example.org\"\n"
                                + "limits:\n  memory: \"8g\"\n");
        final String hash =
                StartCheck.withSettings(context, READY, projectFile, "shop").settingsHash();
        assertThat(RepositoryOwnSettings.allowIfUnchanged(context, "shop", hash)).isTrue();

        Files.writeString(checkout().resolve(".porelo.yml"), "limits:\n  memory: \"16g\"\n");

        final StartCheck.Result again =
                StartCheck.withSettings(context, READY, projectFile, "shop");
        assertThat(again.outcome()).isEqualTo(StartCheck.Outcome.SETTINGS_NOT_ALLOWED);
        assertThat(again.detail()).contains("+ limits.memory = 16g").doesNotContain("8g");
        assertThat(again.settingsToAllow())
                .as("the domain allowed before is not shown again")
                .extracting(each -> each.get("key"))
                .containsExactly("limits.memory");
        assertThat(RepositoryOwnSettings.allowIfUnchanged(context, "shop", hash))
                .as("the hash shown before names what is no longer asked")
                .isFalse();
    }

    @Test
    void aFileThatSetsOnlyItsImageAsksNothing() throws Exception {
        final PoreloContext context = context();
        final Path projectFile = shop(context, "image:\n  run: \"RUN true\"\n");

        assertThat(StartCheck.withSettings(context, READY, projectFile, "shop").outcome())
                .isEqualTo(StartCheck.Outcome.READY);
    }

    @Test
    void theMergedProjectCarriesWhatWasAsked() throws Exception {
        final PoreloContext context = context();
        final Path projectFile =
                shop(context, "egress:\n  domains:\n    - \"build.example.org\"\n");

        final RepositoryOwnSettings.Found found =
                RepositoryOwnSettings.of(
                        context, projectFile, ProjectReader.read(projectFile).repository("shop"));

        assertThat(found).isNotNull();
        assertThat(found.project().egress().domains()).contains("build.example.org");
        assertThat(found.sources())
                .containsEntry("egress.domains[build.example.org]", "porelo.yml of shop");
    }

    @Test
    void aFileThatNamesAnotherProjectIsRefused() throws Exception {
        final PoreloContext context = context();
        final Path projectFile = shop(context, "project:\n  name: \"other\"\n");

        assertThatThrownBy(
                        () ->
                                RepositoryOwnSettings.of(
                                        context,
                                        projectFile,
                                        ProjectReader.read(projectFile).repository("shop")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'project.name'");
        assertThat(StartCheck.withSettings(context, READY, projectFile, "shop").outcome())
                .as("a file that does not read is Start's to report")
                .isEqualTo(StartCheck.Outcome.READY);
    }

    private Path shop(final PoreloContext context, final String porelo) throws Exception {
        Files.createDirectories(checkout());
        Files.writeString(checkout().resolve("porelo.yml"), porelo);
        final DefaultProject project = new DefaultProject(context);
        project.add("https://repo.example.org/shop.git", "shop", checkout().toString());
        return project.file();
    }

    private Path checkout() {
        return dir.resolve("shop");
    }

    private PoreloContext context() {
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
        return new PoreloContext(
                new org.fuin.porelo.core.process.ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }
}
