package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class ProjectBuildsTest {

    private static final String ONLINE =
            """
            project:
              name: "uc"
              security_class: "%s"
              upstream: "git@github.com:example/uc.git"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(final String securityClass, final String builds) {
        return ProjectReader.read(
                new StringReader(ONLINE.formatted(securityClass) + builds), "test.yml");
    }

    @Test
    void anOnlineProjectNamesItsForgeAndTheVaultEntryItsBuildsAreReadWith() {
        final Builds builds =
                read(
                                "online",
                                """
                builds:
                  forge: "github"
                  credential: "github-actions"
                """)
                        .builds();

        assertThat(builds).isEqualTo(new Builds("github", "github-actions", "", Builds.FAILURE));
    }

    @Test
    void aProjectWithoutTheSectionFollowsNoBuild() {
        assertThat(read("online", "").builds()).isNull();
    }

    @Test
    void guardedAndOfflineRefuseItBeforeATaskStarts() {
        for (final String securityClass : new String[] {"guarded", "offline"}) {
            assertThatThrownBy(
                            () ->
                                    read(
                                            securityClass,
                                            "builds:\n  forge: \"github\"\n  credential: \"x\"\n"))
                    .as(securityClass)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("online projects only");
        }
    }

    @Test
    void takesEveryLogWhenAskedAndNothingElse() {
        assertThat(
                        read(
                                        "online",
                                        "builds:\n  forge: \"github\"\n  credential: \"x\"\n  logs: \"all\"\n")
                                .builds()
                                .logs())
                .isEqualTo(Builds.ALL);
        assertThatThrownBy(
                        () ->
                                read(
                                        "online",
                                        "builds:\n  forge: \"github\"\n  credential: \"x\"\n  logs: \"some\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'failure' or 'all'");
    }

    @Test
    void refusesToFollowBuildsWithoutFailedJobLogs() {
        // A task following its pushes needs the failed job's log to fix its work.
        assertThatThrownBy(
                        () ->
                                read(
                                        "online",
                                        """
                builds:
                  forge: "github"
                  credential: "build-token"
                  logs: "none"
                """))
                .as("project build watches must retain failed job logs")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'builds.logs'")
                .hasMessageContaining("'failure' or 'all'");
    }

    @Test
    void refusesAForgeThatIsNoReadersNameAnUnsafeApiAndNoCredential() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "online",
                                        "builds:\n  forge: \"../bin/sh\"\n  credential: \"x\"\n"))
                .as("a forge name cannot escape the build reader directory")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("must name a build reader");
        assertThatThrownBy(
                        () ->
                                read(
                                        "online",
                                        "builds:\n  forge: \"github\"\n  credential: \"x\"\n  api: \"http://forge.example\"\n"))
                .as("a remote forge cannot receive its token over plain HTTP")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("https://");
        assertThatThrownBy(() -> read("online", "builds:\n  forge: \"github\"\n"))
                .as("a build watch needs a vault entry for its forge credential")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("vault entry");
    }

    @Test
    void acceptsPlainHttpOnTheLoopbackOnly() {
        for (final String api :
                new String[] {
                    "http://127.0.0.1:3000/api/v1",
                    "http://[::1]:3000/api/v1",
                    "http://localhost:3000/api/v1",
                    "http://LOCALHOST:3000/api/v1"
                }) {
            assertThat(readBuildsApi(api).builds().api())
                    .as("a forge on this machine's loopback is accepted: %s", api)
                    .isEqualTo(api);
        }
        for (final String api :
                new String[] {
                    "http://forge.example/api/v1",
                    "http://localhost.example/api/v1",
                    "http://127.0.0.1@forge.example/api/v1",
                    "http://127.0.0.2/api/v1",
                    "http://user@127.0.0.1/api/v1",
                    "http://user@[::1]/api/v1",
                    "http://user@localhost/api/v1",
                    "http://@localhost/api/v1",
                    "http://localhost./api/v1",
                    "http://[0:0:0:0:0:0:0:1]/api/v1",
                    "http:///api/v1",
                    "ftp://localhost/api/v1"
                }) {
            assertThatThrownBy(() -> readBuildsApi(api))
                    .as(
                            "an API outside the permitted schemes and loopback forms is refused: %s",
                            api)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("'builds.api'")
                    .hasMessageContaining("this machine's loopback");
        }
    }

    @Test
    void acceptsHttpsAndRefusesMalformedApiUris() {
        final String https = "https://forge.example/api/v1";
        assertThat(readBuildsApi(https).builds().api())
                .as("a remote forge keeps HTTPS support")
                .isEqualTo(https);
        for (final String api :
                new String[] {
                    "https://forge.example/api with spaces",
                    "http://[::1/api/v1",
                    "http://localhost/api/%zz",
                    "https:forge.example/api/v1",
                    "https:///api/v1"
                }) {
            assertThatThrownBy(() -> readBuildsApi(api))
                    .as("a malformed API URI is refused as a project error: %s", api)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("'builds.api'")
                    .hasMessageContaining("this machine's loopback");
        }
    }

    private static Project readBuildsApi(final String api) {
        return read(
                "online",
                """
                builds:
                  forge: "github"
                  credential: "build-token"
                  api: "%s"
                """
                        .formatted(api));
    }

    @Test
    void everyKeyOfTheSectionIsOneTheSchemaDeclares() {
        assertThat(
                        ProjectReader.unknownKeys(
                                new StringReader(
                                        ONLINE.formatted("online")
                                                + "builds:\n  forge: \"github\"\n  credential: \"x\"\n  api: \"https://ghe.example/api/v3\"\n"
                                                + "  logs: \"all\"\n"),
                                "test.yml"))
                .isEmpty();
    }
}
