package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewProfileTest {

    private static final String REVIEW = "/org/fuin/porelo/build/api/review/";

    private static final JsonSchema PROFILE_1 = schema("review-profile-1.schema.json");

    private static final JsonSchema PROFILE_2 = schema("review-profile-2.schema.json");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "forge-page.sarif.json",
                "submitted-comment.sarif.json",
                "submitted-reply.sarif.json"
            })
    void aForgePageASubmittedCommentAndASubmittedReplyHoldToProfileTwo(final String fixture)
            throws IOException {
        assertThat(PROFILE_2.validate(fixture("fixtures/" + fixture), InputFormat.JSON))
                .as("%s against profile 2", fixture)
                .isEmpty();
    }

    // Each failing fixture is a passing one with this one point broken, so it fails for that point.
    @ParameterizedTest
    @CsvSource({
        "submission-with-source-kind.sarif.json, submitted-comment.sarif.json,"
                + " not@$.runs[0].results[0].properties",
        "submission-with-guid.sarif.json, submitted-comment.sarif.json, not@$.runs[0].results[0]",
        "forge-result-without-account-id.sarif.json, forge-page.sarif.json,"
                + " required@$.runs[0].results[0].properties",
        "forge-result-without-created-at.sarif.json, forge-page.sarif.json,"
                + " required@$.runs[0].results[0].properties",
        "pull-request-run-with-urn-repository.sarif.json, forge-page.sarif.json,"
                + " pattern@$.runs[0].versionControlProvenance[0].repositoryUri",
        "result-with-markdown.sarif.json, forge-page.sarif.json,"
                + " additionalProperties@$.runs[0].results[2].message",
        "pull-request-run-naming-project.sarif.json, forge-page.sarif.json,"
                + " not@$.runs[0].properties"
    })
    void aDocumentThatBreaksOnePointOfProfileTwoFailsItsSchemaAtThatPoint(
            final String failing, final String passing, final String expected) throws IOException {
        final String broken = fixture("fixtures/failing/" + failing);
        assertThat(PROFILE_2.validate(broken, InputFormat.JSON))
                .as("%s against profile 2: keyword@instance of each violation", failing)
                .extracting(m -> m.getType() + "@" + m.getInstanceLocation())
                .contains(expected);
        assertThat(differences(parse(fixture("fixtures/" + passing)), parse(broken), ""))
                .as("%s differs from %s in one point", failing, passing)
                .hasSize(1);
    }

    @Test
    void aForgeAccountIsNamedWithItsIdAndNameAndNoOtherSourceCarriesThem() throws IOException {
        final Map<String, Object> page = parse(fixture("fixtures/forge-page.sarif.json"));
        final Map<String, Object> forge = properties(results(page).get(0));
        assertThat(forge.get("porelo/sourceKind"))
                .as("the fixture's first item")
                .isEqualTo("forge-account");

        final Map<String, Object> noName = parse(fixture("fixtures/forge-page.sarif.json"));
        properties(results(noName).get(0)).remove("porelo/forgeAccountName");
        assertThat(violations(noName)).as("a forge account without its name").isNotEmpty();

        final Map<String, Object> ownerWithAccount = ownerPushRun();
        properties(results(ownerWithAccount).get(0)).put("porelo/forgeAccountName", "octocat");
        assertThat(violations(ownerWithAccount))
                .as("the owner account carrying a forge account's name")
                .isNotEmpty();

        final Map<String, Object> taskRun = ownerPushRun();
        final Map<String, Object> task = properties(results(taskRun).get(0));
        task.put("porelo/sourceKind", "task");
        task.put("porelo/taskId", "shop-2");
        assertThat(violations(taskRun)).as("a task with its name").isEmpty();
        task.remove("porelo/taskId");
        assertThat(violations(taskRun)).as("a task without its name").isNotEmpty();
    }

    @Test
    void aForgeAccountsItemCarriesTheTimeItWasWrittenAndNoOtherSourceCarriesOne()
            throws IOException {
        final Map<String, Object> page = parse(fixture("fixtures/forge-page.sarif.json"));
        assertThat(properties(results(page).get(0)).get("porelo/createdAt"))
                .as("the fixture's first item's time")
                .isEqualTo("2026-11-20T09:12:05Z");
        assertThat(violations(page)).as("a forge account's item with its time").isEmpty();

        properties(results(page).get(0)).remove("porelo/createdAt");
        assertThat(violations(page)).as("a forge account's item without its time").isNotEmpty();

        final Map<String, Object> owner = ownerPushRun();
        properties(results(owner).get(0)).put("porelo/createdAt", "2026-11-20T09:12:05Z");
        assertThat(violations(owner)).as("the owner account's item with a time").isNotEmpty();

        final Map<String, Object> taskRun = ownerPushRun();
        final Map<String, Object> task = properties(results(taskRun).get(0));
        task.put("porelo/sourceKind", "task");
        task.put("porelo/taskId", "shop-2");
        task.put("porelo/createdAt", "2026-11-20T09:12:05Z");
        assertThat(violations(taskRun)).as("a task's item with a time").isNotEmpty();
    }

    // Whole seconds in UTC, as the forge says the comment was written, and nothing else.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-11-20T09:12:05.250Z",
                "2026-11-20T09:12:05+01:00",
                "2026-11-20T09:12:05",
                "2026-11-20 09:12:05Z",
                "2026-11-20T09:12Z",
                "2026-11-20t09:12:05z",
                "+12026-11-20T09:12:05Z",
                "2026-11-20T09:12:05Z\n",
                ""
            })
    void aForgeAccountsTimeInAnyOtherFormFailsTheSchema(final String time) throws IOException {
        final Map<String, Object> page = parse(fixture("fixtures/forge-page.sarif.json"));
        properties(results(page).get(0)).put("porelo/createdAt", time);
        assertThat(violations(page)).as("a forge account's time written %s", time).isNotEmpty();
    }

    @Test
    void aSubmissionCarriesNoneOfTheFieldsPoreloAssigns() throws IOException {
        for (final Map.Entry<String, Object> assigned :
                Map.<String, Object>of(
                                "porelo/threadId", "1834527690",
                                "porelo/threadState", "open",
                                "porelo/taskId", "shop-2",
                                "porelo/forgeAccountId", "583231",
                                "porelo/forgeAccountName", "octocat",
                                "porelo/createdAt", "2026-11-20T09:12:05Z")
                        .entrySet()) {
            final Map<String, Object> doc = parse(fixture("fixtures/submitted-comment.sarif.json"));
            properties(results(doc).get(0)).put(assigned.getKey(), assigned.getValue());
            assertThat(violations(doc))
                    .as("a submission naming %s", assigned.getKey())
                    .isNotEmpty();
        }
        final Map<String, Object> paged = parse(fixture("fixtures/submitted-reply.sarif.json"));
        map(run(paged).get("properties")).put("porelo/completeness", "complete");
        assertThat(violations(paged)).as("a submission naming its completeness").isNotEmpty();

        final Map<String, Object> empty = parse(fixture("fixtures/submitted-reply.sarif.json"));
        list(run(empty).get("results")).clear();
        assertThat(violations(empty)).as("a submission without a result").isNotEmpty();
    }

    @Test
    void aPullRequestRunNamesNoPushAndAPushRunNoPullRequest() throws IOException {
        final Map<String, Object> both = parse(fixture("fixtures/forge-page.sarif.json"));
        map(run(both).get("properties")).put("porelo/push", "fix-login");
        assertThat(violations(both)).as("a pull request run naming a push too").isNotEmpty();

        final Map<String, Object> neither = parse(fixture("fixtures/forge-page.sarif.json"));
        map(run(neither).get("properties")).remove("porelo/pullRequest");
        assertThat(violations(neither)).as("a run naming neither").isNotEmpty();

        final Map<String, Object> push = ownerPushRun();
        final Map<String, Object> pushRun = map(run(push).get("properties"));
        assertThat(violations(push)).as("a push run, as profile 1 names it").isEmpty();
        pushRun.remove("porelo/project");
        assertThat(violations(push)).as("a push run without its project").isNotEmpty();

        final Map<String, Object> named = parse(fixture("fixtures/forge-page.sarif.json"));
        map(run(named).get("properties")).put("porelo/repository", "app");
        assertThat(violations(named))
                .as("a pull request run naming a Porelo repository")
                .isNotEmpty();

        for (final String url :
                List.of(
                        "http://github.com/shop/app/pull/42",
                        "https://token@github.com/shop/app/pull/42",
                        "https://github.com/shop/app/pull/42?x=1",
                        "file:///srv/app")) {
            final Map<String, Object> doc = parse(fixture("fixtures/forge-page.sarif.json"));
            map(run(doc).get("properties")).put("porelo/pullRequest", url);
            assertThat(violations(doc)).as("a pull request at %s", url).isNotEmpty();
        }
    }

    @Test
    void theBoundsAJsonSchemaCanStateAreInTheSchema() throws IOException {
        final Map<String, Object> many = parse(fixture("fixtures/forge-page.sarif.json"));
        final List<Object> results = list(run(many).get("results"));
        final Object first = results.get(0);
        while (results.size() <= 100) {
            results.add(first);
        }
        assertThat(violations(many)).as("101 results").isNotEmpty();
        results.remove(100);
        assertThat(violations(many)).as("exactly 100 results").isEmpty();

        final Map<String, Object> longText = parse(fixture("fixtures/submitted-reply.sarif.json"));
        // 8192 bytes as given at most double as written, since each escape doubles one character.
        map(results(longText).get(0).get("message")).put("text", "{{".repeat(8192));
        assertThat(violations(longText))
                .as("8192 braces as given, 16384 characters as written")
                .isEmpty();
        map(results(longText).get(0).get("message")).put("text", "{{".repeat(8192) + "x");
        assertThat(violations(longText)).as("a written text of 16385 characters").isNotEmpty();

        final Map<String, Object> longFix = parse(fixture("fixtures/submitted-comment.sarif.json"));
        insertedContent(results(longFix).get(0)).put("text", "x\n".repeat(4096));
        assertThat(violations(longFix)).as("a replacement of exactly 8192 characters").isEmpty();
        insertedContent(results(longFix).get(0)).put("text", "x\n".repeat(4096) + "x");
        assertThat(violations(longFix)).as("a replacement of 8193 characters").isNotEmpty();

        final String host = "https://github.com/";
        final Map<String, Object> longUrl = parse(fixture("fixtures/forge-page.sarif.json"));
        map(run(longUrl).get("properties"))
                .put("porelo/pullRequest", host + "x".repeat(16384 - host.length()));
        assertThat(violations(longUrl)).as("a string of exactly 16384 characters").isEmpty();
        map(run(longUrl).get("properties"))
                .put("porelo/pullRequest", host + "x".repeat(16385 - host.length()));
        assertThat(violations(longUrl)).as("a string of 16385 characters").isNotEmpty();
    }

    // A regular expression's '$' in Java also matches before a final line feed.
    @Test
    void aValueWithATrailingLineFeedFailsEveryAnchoredPattern() throws IOException {
        final SoftAssertions soft = new SoftAssertions();
        final Map<String, Object> url = parse(fixture("fixtures/forge-page.sarif.json"));
        map(run(url).get("properties"))
                .put("porelo/pullRequest", "https://github.com/shop/app/pull/42\n");
        soft.assertThat(violations(url)).as("a pull request URL").isNotEmpty();

        final Map<String, Object> repository = parse(fixture("fixtures/forge-page.sarif.json"));
        provenance(repository).put("repositoryUri", "https://github.com/shop/app\n");
        soft.assertThat(violations(repository)).as("a repository URL").isNotEmpty();

        final Map<String, Object> commit = parse(fixture("fixtures/forge-page.sarif.json"));
        provenance(commit).put("revisionId", "4e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d\n");
        soft.assertThat(violations(commit)).as("a commit").isNotEmpty();

        final Map<String, Object> uuid = parse(fixture("fixtures/forge-page.sarif.json"));
        results(uuid).get(0).put("guid", "5b2e9d40-1c7a-4f3e-8d6b-2a9c4e7f1b03\n");
        soft.assertThat(violations(uuid)).as("a uuid").isNotEmpty();

        final Map<String, Object> path = parse(fixture("fixtures/forge-page.sarif.json"));
        map(physical(results(path).get(0)).get("artifactLocation")).put("uri", "src/A.java\n");
        soft.assertThat(violations(path)).as("a file").isNotEmpty();

        final Map<String, Object> urn = ownerPushRun();
        provenance(urn).put("repositoryUri", "urn:porelo:repository:shop:app\n");
        soft.assertThat(violations(urn)).as("a Porelo repository").isNotEmpty();

        final Map<String, Object> task = ownerPushRun();
        properties(results(task).get(0)).put("porelo/sourceKind", "task");
        properties(results(task).get(0)).put("porelo/taskId", "shop-2\n");
        soft.assertThat(violations(task)).as("a task's name").isNotEmpty();
        soft.assertAll();
    }

    @Test
    void aPushRunHoldsNoForgeAccountAndAPullRequestPageNothingElse() throws IOException {
        final Map<String, Object> push = ownerPushRun();
        assertThat(violations(push)).as("a push run of the owner account's items").isEmpty();
        final Map<String, Object> forged = properties(results(push).get(1));
        forged.put("porelo/sourceKind", "forge-account");
        forged.put("porelo/forgeAccountId", "583231");
        forged.put("porelo/forgeAccountName", "octocat");
        forged.put("porelo/createdAt", "2026-11-20T09:12:05Z");
        assertThat(violations(push)).as("a push run with a forge account's item").isNotEmpty();

        final Map<String, Object> owner = parse(fixture("fixtures/forge-page.sarif.json"));
        final Map<String, Object> ownerItem = properties(results(owner).get(1));
        ownerItem.put("porelo/sourceKind", "owner-account");
        ownerItem.remove("porelo/forgeAccountId");
        ownerItem.remove("porelo/forgeAccountName");
        ownerItem.remove("porelo/createdAt");
        assertThat(violations(owner)).as("a pull request page with an owner's item").isNotEmpty();

        final Map<String, Object> task = parse(fixture("fixtures/forge-page.sarif.json"));
        final Map<String, Object> taskItem = properties(results(task).get(1));
        taskItem.put("porelo/sourceKind", "task");
        taskItem.put("porelo/taskId", "shop-2");
        taskItem.remove("porelo/forgeAccountId");
        taskItem.remove("porelo/forgeAccountName");
        taskItem.remove("porelo/createdAt");
        assertThat(violations(task)).as("a pull request page with a task's item").isNotEmpty();
    }

    @Test
    void aSubmissionIsAlwaysAPullRequestRun() throws IOException {
        final Map<String, Object> doc = parse(fixture("fixtures/submitted-comment.sarif.json"));
        final Map<String, Object> runProperties = map(run(doc).get("properties"));
        runProperties.remove("porelo/pullRequest");
        runProperties.put("porelo/push", "fix-login");
        runProperties.put("porelo/project", "shop");
        runProperties.put("porelo/repository", "app");
        provenance(doc).put("repositoryUri", "urn:porelo:repository:shop:app");
        assertThat(violations(doc)).as("a submission on a push").isNotEmpty();
    }

    // The JDK's "$" also matches before a final line feed, so each anchored value is tried with
    // one.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "\"revisionId\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | \"",
                "\"porelo/baseRevisionId\": \"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb | \"",
                "\"guid\": \"3f2b8c1e-5d4a-4b7e-9c2d-1a0e6f7b8c9d | \"",
                "\"repositoryUri\": \"urn:porelo:repository:shop:app | \"",
                "\"uri\": \"src/main/java/Login.java | \"",
                "\"porelo/sourceKind\": \"task\", \"porelo/taskId\": \"shop-2 | \""
            })
    void aProfileOneValueEndingInALineFeedFailsItsPattern(final String value, final String end)
            throws IOException {
        final String fixture =
                java.nio.file.Files.readString(
                                java.nio.file.Path.of(
                                        "../../gate/src/test/resources/org/fuin/porelo/gate/review/fixtures/comment-with-fix.sarif.json"),
                                StandardCharsets.UTF_8)
                        .replace(
                                "\"porelo/sourceKind\": \"owner-account\"",
                                value.startsWith("\"porelo/sourceKind\"")
                                        ? value + end
                                        : "\"porelo/sourceKind\": \"owner-account\"");
        assertThat(fixture).as("the fixture holding %s", value).contains(value + end);
        assertThat(PROFILE_1.validate(fixture, InputFormat.JSON))
                .as("%s as it stands", value)
                .isEmpty();
        assertThat(
                        PROFILE_1.validate(
                                fixture.replace(value + end, value + "\\n" + end),
                                InputFormat.JSON))
                .as("%s and a line feed", value)
                .isNotEmpty();
    }

    @Test
    void profileOneStandsBesideProfileTwoAndKnowsNoneOfItsFields() throws IOException {
        for (final String fixture :
                List.of(
                        "forge-page.sarif.json",
                        "submitted-comment.sarif.json",
                        "submitted-reply.sarif.json")) {
            assertThat(PROFILE_1.validate(fixture("fixtures/" + fixture), InputFormat.JSON))
                    .as("%s against profile 1", fixture)
                    .isNotEmpty();
        }
    }

    /** The forge page as a push run whose items are all the owner account's. */
    private static Map<String, Object> ownerPushRun() throws IOException {
        final Map<String, Object> push = parse(fixture("fixtures/forge-page.sarif.json"));
        final Map<String, Object> pushRun = map(run(push).get("properties"));
        pushRun.remove("porelo/pullRequest");
        pushRun.put("porelo/push", "fix-login");
        pushRun.put("porelo/project", "shop");
        pushRun.put("porelo/repository", "app");
        provenance(push).put("repositoryUri", "urn:porelo:repository:shop:app");
        for (final Map<String, Object> result : results(push)) {
            final Map<String, Object> properties = properties(result);
            properties.put("porelo/sourceKind", "owner-account");
            properties.remove("porelo/forgeAccountId");
            properties.remove("porelo/forgeAccountName");
            properties.remove("porelo/createdAt");
            properties.remove("porelo/taskId");
        }
        return push;
    }

    /** The paths at which two parsed documents differ, a missing key counted as one difference. */
    private static List<String> differences(
            final @Nullable Object left, final @Nullable Object right, final String path) {
        final List<String> found = new ArrayList<>();
        if (left instanceof Map<?, ?> l && right instanceof Map<?, ?> r) {
            final Set<Object> keys = new java.util.LinkedHashSet<>(l.keySet());
            keys.addAll(r.keySet());
            for (final Object key : keys) {
                found.addAll(differences(l.get(key), r.get(key), path + "/" + key));
            }
        } else if (left instanceof List<?> l
                && right instanceof List<?> r
                && l.size() == r.size()) {
            for (int i = 0; i < l.size(); i++) {
                found.addAll(differences(l.get(i), r.get(i), path + "/" + i));
            }
        } else if (!Objects.equals(left, right)) {
            found.add(path);
        }
        return found;
    }

    private static Set<ValidationMessage> violations(final Map<String, Object> doc) {
        return PROFILE_2.validate(Json.write(doc), InputFormat.JSON);
    }

    private static JsonSchema schema(final String name) {
        try (InputStream in = ReviewProfileTest.class.getResourceAsStream(REVIEW + name)) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Objects.requireNonNull(in, name));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture(final String name) throws IOException {
        try (InputStream in = ReviewProfileTest.class.getResourceAsStream(REVIEW + name)) {
            return new String(
                    Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> parse(final String sarif) {
        return map(Json.parseExact(sarif));
    }

    private static Map<String, Object> run(final Map<String, Object> doc) {
        return map(list(doc.get("runs")).get(0));
    }

    private static Map<String, Object> provenance(final Map<String, Object> doc) {
        return map(list(run(doc).get("versionControlProvenance")).get(0));
    }

    private static List<Map<String, Object>> results(final Map<String, Object> doc) {
        final List<Map<String, Object>> results = new ArrayList<>();
        list(run(doc).get("results")).forEach(r -> results.add(map(r)));
        return results;
    }

    private static Map<String, Object> physical(final Map<String, Object> result) {
        return map(map(list(result.get("locations")).get(0)).get("physicalLocation"));
    }

    private static Map<String, Object> properties(final Map<String, Object> result) {
        return map(result.get("properties"));
    }

    private static Map<String, Object> insertedContent(final Map<String, Object> result) {
        final Map<String, Object> change =
                map(list(map(list(result.get("fixes")).get(0)).get("artifactChanges")).get(0));
        return map(map(list(change.get("replacements")).get(0)).get("insertedContent"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final @Nullable Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final @Nullable Object value) {
        return (List<Object>) Objects.requireNonNull(value);
    }
}
