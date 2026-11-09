package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.TestPrograms;
import org.junit.jupiter.api.Test;

/** Prevents a worker from retaining more output than its bounded verdicts can require. */
class HostPushOutputTest {
    static int largestAnswer(int commits) {
        final var signer =
                HostJson.fields(
                        "kind",
                        "task",
                        "fingerprint",
                        HostReadFixture.FINGERPRINT,
                        "principal",
                        "\u0800".repeat(16384),
                        "certificate",
                        "certificate",
                        "signed_by",
                        HostReadFixture.FINGERPRINT);
        final var row =
                HostJson.fields(
                        "commit",
                        "a".repeat(64),
                        "ref",
                        "refs/" + "\"".repeat(1000),
                        "verdict",
                        "refused",
                        "signer",
                        signer,
                        "reason",
                        "\u0800".repeat(16384));
        return org.fuin.porelo.wire.Json.write(
                                HostJson.fields(
                                        "checked_at",
                                        "9999-12-31T23:59:59Z",
                                        "accepted",
                                        false,
                                        "commits",
                                        java.util.Collections.nCopies(commits, row),
                                        "reason",
                                        ""))
                        .getBytes(StandardCharsets.UTF_8)
                        .length
                + 1;
    }

    @Test
    void aWorkerCannotPrintBeyondTheMeasuredVerdictBudget() throws Exception {
        final int bytes = largestAnswer(1);
        final var runner =
                new HostPrograms(
                        Duration.ofSeconds(5),
                        ProjectRuntime.outputLimit(List.of("--unit=porelo-host-push-fixture"), 1),
                        TestPrograms::find);
        assertThat(
                        runner.run(List.of("head", "-c", Integer.toString(bytes), "/dev/zero"))
                                .output()
                                .length())
                .as("the largest serialized verdict and framing must fit")
                .isEqualTo(bytes);
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        List.of(
                                                "head",
                                                "-c",
                                                Integer.toString(bytes + 1),
                                                "/dev/zero")))
                .as("worker stdout cannot exceed the maximum serialized verdict budget")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aWorkerCannotRetainMoreThanOneMebibyteOfStandardError(boolean closedOutput) {
        final var runner =
                new HostPrograms(
                        Duration.ofSeconds(2),
                        ProjectRuntime.outputLimit(
                                List.of("--unit=porelo-host-push-fixture"), 10000),
                        TestPrograms::find);
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        List.of(
                                                "sh",
                                                "-c",
                                                (closedOutput ? "exec 1>&-; " : "")
                                                        + "head -c 1048577 /dev/zero >&2; sleep 30")))
                .as("excessive stderr must stop the worker immediately, independently of stdout")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit");
    }
}
