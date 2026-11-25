package org.fuin.porelo.build.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The review profile's page states the bounds {@link ProfileCheck} holds, with its numbers. */
@Tag("documents")
class ProfileCheckDocumentTest {

    @Test
    void theProfilesBoundsTableStatesEachBoundTheCheckHolds() throws IOException {
        final String page =
                Files.readString(Path.of("../../doc/review-profile.md"), StandardCharsets.UTF_8);
        final List<String> rows =
                List.of(
                        "| a region at most " + ProfileCheck.MAX_REGION_LINES + " lines |",
                        "| a replacement (`insertedContent`) at most its anchored lines plus "
                                + ProfileCheck.EXTRA_REPLACEMENT_LINES
                                + " lines |",
                        "| a replacement's line at most "
                                + ProfileCheck.MAX_REPLACEMENT_LINE
                                + " characters |",
                        "| a replacement at most "
                                + ProfileCheck.MAX_TEXT_BYTES / 1024
                                + " KiB in all",
                        "| `message.text` at most "
                                + ProfileCheck.MAX_TEXT_BYTES
                                + " bytes of UTF-8 per result as given",
                        "`https://<host>[:<port>]` and "
                                + ProfileCheck.MIN_REPOSITORY_SEGMENTS
                                + " to "
                                + ProfileCheck.MAX_REPOSITORY_SEGMENTS
                                + " path segments",
                        "that URL and 1 to "
                                + ProfileCheck.MAX_PULL_REQUEST_SEGMENTS
                                + " more segments, the last all digits",
                        "each segment 1 to " + ProfileCheck.MAX_URL_SEGMENT + " of");
        for (final String row : rows) {
            assertThat(page).as("the profile's page stating %s", row).contains(row);
        }
    }
}
