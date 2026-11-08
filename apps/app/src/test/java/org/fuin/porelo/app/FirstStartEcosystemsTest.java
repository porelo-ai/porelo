package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.EcosystemException;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.ecosystem.api.Unplaced;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ecosystems a machine has, and what each answers for a checkout as a first start asks it. */
class FirstStartEcosystemsTest {

    @TempDir Path root;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<String> asked = new ArrayList<>();

    private PoreloContext context;

    private Path checkout;

    @BeforeEach
    void machine() throws Exception {
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(key -> null, root), root.resolve("bin")),
                        args -> 0,
                        key -> null);
        checkout = Files.createDirectories(root.resolve("work/shop")).toRealPath();
        runner.answering("--show-toplevel", checkout.toString());
        runner.answering("get-url origin", "https://forge.example/acme/shop.git");
    }

    /** Installs a plugin that describes itself as the given JSON says. */
    private Path plugin(final String name, final String description) throws IOException {
        final Path plugin =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve(name);
        Files.writeString(plugin, "#!/bin/sh\nprintf '%s' '" + description + "'\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
        return plugin;
    }

    private Path plugin(final String name) throws IOException {
        return plugin(
                name,
                "{\"protocolVersion\":2,\"ecosystem\":\"" + name + "\",\"version\":\"1.4.0\"}");
    }

    /** Answers by the plugin's name, recording where each was asked. */
    private final EcosystemDetector detector =
            (plugin, directory, home) -> {
                asked.add(plugin.name() + " " + directory + " " + home);
                return switch (plugin.name()) {
                    case "maven" ->
                            new Detection(
                                    List.of(
                                            new Finding(
                                                    Finding.Part.TOOLS,
                                                    "java.version",
                                                    "25",
                                                    "pom.xml",
                                                    14,
                                                    Finding.Origin.CHECKOUT,
                                                    false,
                                                    "")),
                                    List.of(new Unplaced("Dockerfile", 3, "a container\u001b[2J")));
                    case "npm" ->
                            throw new EcosystemRefused(EcosystemRefused.Reason.NOT_RECOGNIZED, "");
                    case "pip" ->
                            throw new EcosystemRefused(
                                    EcosystemRefused.Reason.UNREADABLE, "setup.cfg: permission");
                    default -> throw new EcosystemException("gone");
                };
            };

    private FirstStart firstStart() {
        return new FirstStart(context, detector);
    }

    @Test
    void listsEveryInstalledPluginWithItsVersionsAndWhyOneIsRefused() throws Exception {
        final Path maven = plugin("maven");
        final Path old = plugin("old", "{\"protocolVersion\":0,\"ecosystem\":\"old\"}");

        final FirstStart.Ecosystems answer = firstStart().ecosystems(null);

        assertThat(answer.plugins())
                .as("each plugin as InstalledEcosystems describes it, the refused one too")
                .extracting(
                        InstalledEcosystems.Description::name,
                        InstalledEcosystems.Description::executable,
                        InstalledEcosystems.Description::version,
                        InstalledEcosystems.Description::protocolVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                maven.getFileName().toString(), maven, "1.4.0", 2),
                        org.assertj.core.groups.Tuple.tuple("old", old, null, null));
        assertThat(answer.plugins().getLast().refusal()).as("why it is not used").isNotNull();
        assertThat(answer.placing()).as("no checkout, nothing asked").isNull();
        assertThat(asked).as("listing asks no plugin about a directory").isEmpty();
        assertThat(answer.asMap()).as("the wire leaves placing out").doesNotContainKey("placing");
    }

    @Test
    void answersForACheckoutWhatEachPluginFindsWhereTheFirstStartAsksIt() throws Exception {
        plugin("maven");

        final FirstStart.Ecosystems answer = firstStart().ecosystems(checkout);

        assertThat(asked)
                .as("the directory and home a first start's detection is given")
                .containsExactly("maven " + checkout + " " + context.paths().xdg().home());
        final FirstStart.Placing maven = answer.placing().getFirst();
        assertThat(maven.outcome())
                .as("it recognized its tool")
                .isEqualTo(FirstStart.Asking.DETECTED);
        assertThat(maven.detection().findings())
                .as("its findings whole")
                .extracting(Finding::key, Finding::value, Finding::line)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("java.version", "25", 14));
        assertThat(maven.files()).as("a first start asks no plugin for settings files").isEmpty();
        assertThat(maven.asMap().get("unplaced"))
                .as("what it cannot place, with its words made printable")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "file",
                                        "Dockerfile",
                                        "line",
                                        3,
                                        "note",
                                        "a container?[2J")));
    }

    @Test
    void aPluginThatCannotAnswerIsNamedAndTheOthersStillAnswer() throws Exception {
        plugin("broken");
        plugin("maven");
        plugin("npm");
        plugin("pip");

        final List<FirstStart.Placing> placing = firstStart().ecosystems(checkout).placing();

        assertThat(placing)
                .as("every usable plugin is asked, in the first start's order")
                .extracting(each -> each.plugin().name(), FirstStart.Placing::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("broken", FirstStart.Asking.FAILED),
                        org.assertj.core.groups.Tuple.tuple("maven", FirstStart.Asking.DETECTED),
                        org.assertj.core.groups.Tuple.tuple(
                                "npm", FirstStart.Asking.NOT_RECOGNIZED),
                        org.assertj.core.groups.Tuple.tuple("pip", FirstStart.Asking.UNREADABLE));
        assertThat(placing)
                .extracting(FirstStart.Placing::note)
                .as("the notes a first start gives for each")
                .containsExactly(
                        "broken failed to answer",
                        "",
                        "",
                        "pip could not read: setup.cfg: permission");
    }

    @Test
    void asksWithoutWritingAnything() throws Exception {
        plugin("maven");
        final List<Path> before = everything();

        firstStart().ecosystems(checkout);

        assertThat(everything())
                .as("no file is written, in the checkout or the machine")
                .isEqualTo(before);
    }

    @Test
    void refusesADirectoryInNoCheckout() throws Exception {
        plugin("maven");
        final Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        runner.failing("--show-toplevel", 128, "not a git repository");

        assertThatThrownBy(() -> firstStart().ecosystems(elsewhere))
                .as("as a first start refuses it")
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .isEqualTo(FirstStart.Reason.NOT_A_CHECKOUT));
        assertThat(asked).as("no plugin is asked about it").isEmpty();
    }

    @Test
    void aPlacedFilesContentNeverAppearsInTheAnswer() {
        final FirstStart.Placing placing =
                new FirstStart.Placing(
                        new InstalledEcosystems.Description(
                                "maven", root.resolve("maven"), null, "1.4.0", 2),
                        FirstStart.Asking.DETECTED,
                        new Detection(List.of(), List.of()),
                        List.of(new File(".m2/settings.xml", "<password>hunter2</password>", true)),
                        "");

        final Map<String, Object> wire =
                new FirstStart.Ecosystems(List.of(placing.plugin()), List.of(placing)).asMap();

        assertThat(String.valueOf(wire))
                .as("a settings file may hold a credential, so only its path crosses")
                .doesNotContain("hunter2", "content");
        assertThat(placing.asMap().get("files"))
                .as("its path and whether only its owner reads it")
                .isEqualTo(List.of(Map.of("path", ".m2/settings.xml", "ownerOnly", true)));
    }

    private List<Path> everything() throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            return all.sorted().toList();
        }
    }
}
