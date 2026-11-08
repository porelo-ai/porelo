package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.Test;

/** Tests for naming which delivery a pushed file came in through. */
class DeliveredOriginsTest {

    private static final String SHA1 = "45b983be36b73c0788dc9cbcb76cbb80fc7bb057";

    private static final String SHA256 =
            "96c18f0297e38d01f4b2dacddea4259aea6b2961eb0822bd2c0c3f6029030045";

    private static HandIns.Delivered handedIn(final String name, final String by) {
        return new HandIns.Delivered(
                new HandIns.HandedFile(name, 3, "aa", "2026-11-08T09:00:00Z", by, "run"),
                Map.of("sha1", SHA1, "sha256", SHA256));
    }

    @Test
    void aPushedFileWithAHandedInFilesContentNamesThatHandInAtAnyPath() {
        final Map<String, DeliveredOrigins.Origin> origins =
                DeliveredOrigins.of(
                        List.of(handedIn("h.txt", "alice")),
                        new GitGate.Blobs(
                                "sha1", Map.of("docs/copied.txt", SHA1, "own.txt", "beef")));

        assertThat(origins)
                .as("only the file with the delivered content is named")
                .containsOnlyKeys("docs/copied.txt");
        assertThat(origins.get("docs/copied.txt").asMap())
                .as("the hand-in it came in through")
                .containsEntry("kind", "HAND_IN")
                .containsEntry("name", "h.txt")
                .containsEntry("by", "alice")
                .containsEntry("at", "2026-11-08T09:00:00Z");
    }

    @Test
    void theMirrorsObjectFormatChoosesWhichIdIsCompared() {
        final GitGate.Blobs sha256Mirror = new GitGate.Blobs("sha256", Map.of("h.txt", SHA256));
        final GitGate.Blobs sha1Mirror = new GitGate.Blobs("sha1", Map.of("h.txt", SHA256));

        assertThat(DeliveredOrigins.of(List.of(handedIn("h.txt", "alice")), sha256Mirror))
                .as("a sha256 mirror matches the sha256 id")
                .containsOnlyKeys("h.txt");
        assertThat(DeliveredOrigins.of(List.of(handedIn("h.txt", "alice")), sha1Mirror))
                .as("the other format's id is never taken for this one")
                .isEmpty();
    }

    @Test
    void aTaskGivenNothingNamesNoOrigin() {
        assertThat(DeliveredOrigins.of(List.of(), new GitGate.Blobs("sha1", Map.of("a", SHA1))))
                .as("everything is the agent's own")
                .isEmpty();
    }

    @Test
    void theLatestOfTwoDeliveriesWithTheSameContentIsNamed() {
        final Map<String, DeliveredOrigins.Origin> origins =
                DeliveredOrigins.of(
                        List.of(handedIn("first.txt", "alice"), handedIn("second.txt", "bob")),
                        new GitGate.Blobs("sha1", Map.of("h.txt", SHA1)));

        assertThat(origins.get("h.txt").asMap())
                .as("the one the agent saw last")
                .containsEntry("name", "second.txt");
    }
}
