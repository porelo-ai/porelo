package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A start given work on a host never works on a copy that misses central's newest main. */
class CentralFreshnessTest {
    @TempDir Path dir;

    private Project project;
    private OrganizationProjects.Placed host;
    private int follows;

    @BeforeEach
    void aProjectOfTwoRepositoriesCarriedHere() throws Exception {
        final Path file = dir.resolve("demo.yml");
        Files.writeString(
                file,
                """
                project:
                  name: demo
                  security_class: online
                  central: https://central.example/demo.git
                image:
                  from: ubuntu:24.04
                repositories:
                  pi:
                    upstream: "https://upstream.example/pi.git"
                    central: "https://central.example/pi.git"
                """);
        project = ProjectReader.read(file);
        host =
                new OrganizationProjects.Placed(
                        "demo",
                        "0123456789abcdef0123456789abcdef01234567",
                        "https://central.example/demo.git",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of(),
                        null);
    }

    private HostCentral published(String piState) throws Exception {
        final Path central = Files.createDirectories(dir.resolve("central"));
        Files.writeString(
                central.resolve("demo.json"),
                "{\"project\":\"demo\",\"read_at\":\"2026-11-15T10:00:00Z\",\"repositories\":"
                        + "[{\"repository\":\"pi\",\"state\":\""
                        + piState
                        + "\""
                        + (piState.equals("ahead") ? ",\"commits\":1" : "")
                        + "}]}");
        return new HostCentral(
                central,
                path -> Map.of("uid", 0, "mode", Files.isDirectory(path) ? 0040755 : 0100640));
    }

    private String refusal(String piState, int followed) throws Exception {
        return refusal(piState, followed, java.time.Instant.parse("2026-11-15T09:59:58Z"));
    }

    private String refusal(String piState, int followed, java.time.Instant asked) throws Exception {
        return CentralFreshness.refusal(
                () -> {
                    follows++;
                    return followed;
                },
                published(piState),
                project,
                host,
                pi(),
                asked);
    }

    private Repository pi() {
        return project.repositories().stream()
                .filter(row -> row.name().equals("pi"))
                .findFirst()
                .orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"up-to-date", "ahead"})
    void aCopyHoldingCentralsMainStartsAfterOneFollow(String state) throws Exception {
        assertThat(refusal(state, 0)).as("the copy holds central's main").isNull();
        assertThat(follows).as("the host was asked to follow first").isEqualTo(1);
    }

    @Test
    void aCopyBehindCentralThatTheFollowDidNotMoveRefusesTheStart() throws Exception {
        // A task given work on the host's older copy would work on code its work was not planned
        // against.
        assertThat(refusal("moved-on", 0))
                .as("refused, naming the way back")
                .contains("'pi'")
                .contains("holds commits this host's copy lacks")
                .contains("porelo host pull demo pi");
    }

    @Test
    void aStateOlderThanTheFollowAskedForSaysNothingOfNow() throws Exception {
        // A pass that skipped the project leaves an older pass's up-to-date behind it.
        assertThat(refusal("up-to-date", 0, java.time.Instant.parse("2026-11-15T10:05:00Z")))
                .as("published before this start's follow")
                .contains("is not known");
    }

    @Test
    void aDivergedCopyRefusesTheStartAndNamesBothWays() throws Exception {
        assertThat(refusal("diverged", 0))
                .contains("each hold commits the other lacks")
                .contains("porelo host pull demo pi")
                .contains("porelo host push demo pi");
    }

    @Test
    void anUnknownStandingRefusesRatherThanStartingOnAnOldCopy() throws Exception {
        assertThat(refusal("cannot-say", 0)).contains("is not known");
        assertThat(refusal("up-to-date", 1))
                .as("a follow that did not run leaves the published state old")
                .contains("is not known")
                .contains("could not follow central now, exit 1");
    }
}
