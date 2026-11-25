package org.fuin.porelo.build.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.contrastsecurity.sarif.SarifSchema210;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.build.api.review.ProfileCheck.Channel;
import org.fuin.porelo.build.api.review.SarifRefused.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfileCheckTest {

    private static final String REVIEW = "/org/fuin/porelo/build/api/review/";

    // Every text fixture carries it where the text is broken; no refusal may repeat it.
    private static final String MARKER = "MARKER-5e1f";

    private static final String TEXT = "/runs/0/results/0/message/text";

    private static final String URI =
            "/runs/0/results/0/locations/0/physicalLocation/artifactLocation/uri";

    private static final String REGION = "/runs/0/results/0/locations/0/physicalLocation/region";

    private static final String CREATED_AT = "/runs/0/results/0/properties/porelo~1createdAt";

    private static final String CHANGE = "/runs/0/results/0/fixes/0/artifactChanges/0";

    private static final String INSERTED = CHANGE + "/replacements/0/insertedContent/text";

    // Each fixture is a passing one with one point broken: refused by that point's rule, at its
    // path.
    @ParameterizedTest
    @CsvSource({
        "text-bidi, TASK, FORMAT_CHARACTER, " + TEXT,
        "text-zero-width, TASK, FORMAT_CHARACTER, " + TEXT,
        "text-control, TASK, CONTROL_CHARACTER, " + TEXT,
        "text-line-separator, TASK, SEPARATOR_CHARACTER, " + TEXT,
        "text-lone-surrogate, TASK, LONE_SURROGATE, " + TEXT,
        "text-too-large, TASK, TEXT_SIZE, " + TEXT,
        "text-link, TASK, MESSAGE_ESCAPE, " + TEXT,
        "text-placeholder, TASK, MESSAGE_ESCAPE, " + TEXT,
        "reply-to-zero-width, TASK, FORMAT_CHARACTER, /runs/0/results/0/properties/porelo~1replyTo",
        "forge-account-name-bidi, ADAPTER, FORMAT_CHARACTER, /runs/0/results/0/properties/porelo~1forgeAccountName",
        "created-at-not-a-date, ADAPTER, CREATED_AT, " + CREATED_AT,
        "uri-encoded-unreserved, TASK, ARTIFACT_URI, " + URI,
        "uri-encoded-dot-segment, TASK, ARTIFACT_URI, " + URI,
        "uri-encoded-slash, TASK, ARTIFACT_URI, " + URI,
        "uri-backslash, TASK, ARTIFACT_URI, " + URI,
        "uri-control, TASK, ARTIFACT_URI, " + URI,
        "uri-not-utf8, TASK, ARTIFACT_URI, " + URI,
        "region-over-100-lines, TASK, REGION, " + REGION,
        "region-reversed, TASK, REGION, " + REGION,
        "fix-other-lines, TASK, FIX_ANCHOR, " + CHANGE + "/replacements/0/deletedRegion",
        "fix-other-file, TASK, FIX_ANCHOR, " + CHANGE + "/artifactLocation/uri",
        "replacement-too-many-lines, TASK, REPLACEMENT_LINES, " + INSERTED,
        "replacement-long-line, TASK, REPLACEMENT_LINE_LENGTH, " + INSERTED,
        "replacement-too-large, TASK, REPLACEMENT_SIZE, " + INSERTED,
        "replacement-control, TASK, CONTROL_CHARACTER, " + INSERTED,
        "adapter-push-run, ADAPTER, CHANNEL, /runs/0/properties",
        "url-path-payload, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-percent-encoded-segment, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-pull-request-elsewhere, TASK, URL_SHAPE, /runs/0/properties/porelo~1pullRequest",
        "url-repository-dot-segment, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-pull-request-dot-segment, TASK, URL_SHAPE, /runs/0/properties/porelo~1pullRequest",
        "url-host-empty-label, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-host-label-leading-hyphen, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-host-label-trailing-hyphen, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-host-trailing-dot, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-port-zero, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-port-too-high, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-port-leading-zero, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-host-label-too-long, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri",
        "url-host-too-long, TASK, URL_SHAPE, /runs/0/versionControlProvenance/0/repositoryUri"
    })
    void anAdversarialFixtureIsRefusedByItsRuleAtItsPathAndPassesWithoutIt(
            final String fixture, final Channel channel, final Rule rule, final String path)
            throws Exception {
        final SarifSchema210 document =
                SarifReading.read(resource("adversarial/" + fixture + ".sarif.json"));
        final SarifRefused refused =
                catchThrowableOfType(
                        SarifRefused.class, () -> ProfileCheck.check(channel, document));
        assertThat(refused).as("%s's refusal", fixture).isNotNull();
        assertThat(refused.rule()).as("the rule %s is refused by", fixture).isEqualTo(rule);
        assertThat(refused.path()).as("where %s is refused", fixture).isEqualTo(path);
        assertThat(String.valueOf(refused.getMessage()) + refused)
                .as("%s's refusal, which never repeats the document", fixture)
                .doesNotContain(MARKER)
                .doesNotContain("5e1f");
        assertThat(ProfileCheck.check(channel, document, Set.of(rule)))
                .as("%s with its rule left out, so it breaks no other", fixture)
                .isSameAs(document);
    }

    @ParameterizedTest
    @CsvSource({
        "fixtures/submitted-comment.sarif.json, TASK",
        "fixtures/submitted-reply.sarif.json, TASK",
        "fixtures/forge-page.sarif.json, ADAPTER"
    })
    void aDocumentHoldingToTheProfileAndItsChannelPasses(
            final String fixture, final Channel channel) throws Exception {
        final SarifSchema210 document = SarifReading.read(resource(fixture));
        assertThat(ProfileCheck.check(channel, document))
                .as("%s from %s", fixture, channel)
                .isSameAs(document);
    }

    @ParameterizedTest
    @CsvSource({
        "https://github.com/shop/app, https://github.com/shop/app/pull/42",
        "https://gitea.example.org:3000/shop/app, https://gitea.example.org:3000/shop/app/pulls/42",
        "https://gitlab.example.org/group/sub/app, https://gitlab.example.org/group/sub/app/-/merge_requests/42"
    })
    void aForgesRepositoryAndPullRequestUrlsPass(final String repository, final String pullRequest)
            throws Exception {
        final String reply =
                new String(resource("fixtures/submitted-reply.sarif.json"), StandardCharsets.UTF_8)
                        .replace("https://github.com/shop/app/pull/42", pullRequest)
                        .replace("\"https://github.com/shop/app\"", "\"" + repository + "\"");
        final SarifSchema210 document = SarifReading.read(reply.getBytes(StandardCharsets.UTF_8));
        assertThat(ProfileCheck.check(Channel.TASK, document))
                .as("a pull request at %s", pullRequest)
                .isSameAs(document);
    }

    @ParameterizedTest
    @CsvSource({
        "fixtures/submitted-reply.sarif.json, ADAPTER, /properties/porelo~1documentKind",
        "fixtures/forge-page.sarif.json, TASK, /properties/porelo~1documentKind"
    })
    void aDocumentOfTheOtherChannelsKindIsRefused(
            final String fixture, final Channel channel, final String path) throws Exception {
        final SarifSchema210 document = SarifReading.read(resource(fixture));
        final SarifRefused refused =
                catchThrowableOfType(
                        SarifRefused.class, () -> ProfileCheck.check(channel, document));
        assertThat(refused).as("%s from %s", fixture, channel).isNotNull();
        assertThat(refused.rule())
                .as("the rule %s from %s is refused by", fixture, channel)
                .isEqualTo(Rule.CHANNEL);
        assertThat(refused.path())
                .as("where %s from %s is refused", fixture, channel)
                .isEqualTo(path);
    }

    // Each holds the schema's pattern; only a calendar tells it is no time that was.
    @ParameterizedTest
    @CsvSource({
        "2026-02-30T09:12:05Z, false",
        "2025-02-29T09:12:05Z, false",
        "2026-13-01T09:12:05Z, false",
        "2026-00-10T09:12:05Z, false",
        "2026-11-00T09:12:05Z, false",
        "2026-11-20T24:00:00Z, false",
        "2026-11-20T09:60:05Z, false",
        "2026-12-31T23:59:60Z, false",
        "2024-02-29T23:59:59Z, true",
        "2026-11-20T00:00:00Z, true"
    })
    void aForgeCommentsTimeIsTakenOnlyWhenItIsARealUtcTime(final String time, final boolean real)
            throws Exception {
        final String page =
                new String(resource("fixtures/forge-page.sarif.json"), StandardCharsets.UTF_8);
        final String written = "\"porelo/createdAt\": \"2026-11-20T09:12:05Z\"";
        assertThat(page).as("the page holding its first item's time").contains(written);
        final SarifSchema210 document =
                SarifReading.read(
                        page.replace(written, "\"porelo/createdAt\": \"" + time + "\"")
                                .getBytes(StandardCharsets.UTF_8));
        final SarifRefused refused =
                catchThrowableOfType(
                        SarifRefused.class, () -> ProfileCheck.check(Channel.ADAPTER, document));
        if (real) {
            assertThat(refused).as("%s, a time that was", time).isNull();
        } else {
            assertThat(refused).as("%s's refusal", time).isNotNull();
            assertThat(refused.rule())
                    .as("the rule %s is refused by", time)
                    .isEqualTo(Rule.CREATED_AT);
            assertThat(refused.path()).as("where %s is refused", time).isEqualTo(CREATED_AT);
        }
    }

    @Test
    void aProfileOneDocumentComesThroughNoChannel() throws Exception {
        // Profile 1 is what Porelo writes itself; its fixtures are read where they lie.
        final SarifSchema210 document =
                SarifReading.read(
                        Files.readAllBytes(
                                Path.of(
                                        "../../gate/src/test/resources/org/fuin/porelo/gate/review/fixtures/comment-with-fix.sarif.json")));
        for (final Channel channel : Channel.values()) {
            final SarifRefused refused =
                    catchThrowableOfType(
                            SarifRefused.class, () -> ProfileCheck.check(channel, document));
            assertThat(refused).as("profile 1 from %s", channel).isNotNull();
            assertThat(refused.rule())
                    .as("the rule profile 1 from %s is refused by", channel)
                    .isEqualTo(Rule.CHANNEL);
            assertThat(refused.path())
                    .as("where profile 1 from %s is refused", channel)
                    .isEqualTo("/properties/porelo~1profileVersion");
        }
    }

    private static byte[] resource(final String name) throws IOException {
        try (InputStream in = ProfileCheckTest.class.getResourceAsStream(REVIEW + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }
}
