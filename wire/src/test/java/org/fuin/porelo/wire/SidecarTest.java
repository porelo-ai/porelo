package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link Sidecar}. */
class SidecarTest {

    private static final Sidecar SIDECAR =
            new Sidecar(
                    Sidecar.VERSION,
                    "uc",
                    "guarded",
                    "/run/user/1000/porelo/uc/ruleset.nft",
                    "/run/user/1000/porelo/uc/dns.conf",
                    "/usr/bin/porelo",
                    "/run/user/1000/porelo/uc");

    @Test
    void preservesOptionalIdentitySpellingsAcrossAReadAndWrite() {
        // Dropping a recorded spelling makes an existing task unresolvable after restart.
        final String recorded =
                SIDECAR.toJson()
                        .replace(
                                "{",
                                "{\"task\":\"work\",\"projectShort\":\"ws\",\"taskShort\":\"x\",");
        assertThat(Json.parse(Sidecar.fromJson(recorded).toJson()))
                .as("the hook record retains the task's immutable spelling")
                .isInstanceOfSatisfying(
                        java.util.Map.class,
                        values ->
                                assertThat(values)
                                        .containsEntry("task", "work")
                                        .containsEntry("projectShort", "ws")
                                        .containsEntry("taskShort", "x"));
    }

    @Test
    void carriesTheTasksLimitsLineWholeForItsHooks() {
        final String json = withoutLimits().replace("{", "{\"limits\":\"machines=2\",");

        assertThat(Json.parse(Sidecar.fromJson(json).toJson()))
                .as("read as the factors, written back with all four")
                .isInstanceOfSatisfying(
                        java.util.Map.class,
                        values ->
                                assertThat(values)
                                        .containsEntry(
                                                "limits",
                                                "services=1.0,machines=2.0,polling=1.0,retries=1.0"));
    }

    @Test
    void refusesASidecarWithoutALimitsLineOrWithABadOne() {
        final String without = withoutLimits();
        assertThatThrownBy(() -> Sidecar.fromJson(without))
                .as("a hook never guesses the task's factors, and says why it has none")
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("'limits' is missing")
                .hasMessageContaining("started by an older Porelo")
                .hasMessageContaining("must be recreated");
        assertThatThrownBy(() -> Sidecar.fromJson(without.replace("{", "{\"limits\":\"\",")))
                .as("an empty line is no line, never the defaults")
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("limits")
                .hasMessageContaining("an empty line names no factor");
        assertThatThrownBy(
                        () -> Sidecar.fromJson(without.replace("{", "{\"limits\":\"machines=0\",")))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("limits")
                .hasMessageContaining("machines 0 lies outside 0.1 to 100");
    }

    /** The sidecar's document with its limits line taken out, wherever it stands. */
    private static String withoutLimits() {
        return SIDECAR.toJson().replaceAll(",?\"limits\":\"[^\"]*\"", "").replace("{,", "{");
    }

    @Test
    void survivesARoundTrip() {

        assertThat(Sidecar.fromJson(SIDECAR.toJson())).isEqualTo(SIDECAR);
    }

    @Test
    void survivesARoundTripThroughAFile(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("nested/sidecar.json");
        SIDECAR.writeTo(file);

        assertThat(Sidecar.readFrom(file)).isEqualTo(SIDECAR);
    }

    @Test
    void refusesAVersionItDoesNotKnow() {

        // The nft hook fails closed, so acting on a misread file is worse than refusing it.
        final String json = SIDECAR.toJson().replace("\"version\":3", "\"version\":99");

        assertThatThrownBy(() -> Sidecar.fromJson(json))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Unsupported sidecar version 99");
    }

    @Test
    void refusesAMissingField() {

        assertThatThrownBy(() -> Sidecar.fromJson("{\"version\":3,\"project\":\"uc\"}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("securityClass");
    }

    @Test
    void refusesSomethingThatIsNotAnObject() {

        assertThatThrownBy(() -> Sidecar.fromJson("[]"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void namesTheAnnotationTheHooksAreGatedOn() {

        assertThat(Sidecar.ANNOTATION).isEqualTo("org.fuin.porelo.sidecar");
    }
}
