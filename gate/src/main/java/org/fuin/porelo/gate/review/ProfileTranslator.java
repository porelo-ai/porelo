package org.fuin.porelo.gate.review;

import com.contrastsecurity.sarif.ArtifactChange;
import com.contrastsecurity.sarif.Fix;
import com.contrastsecurity.sarif.PhysicalLocation;
import com.contrastsecurity.sarif.PropertyBag;
import com.contrastsecurity.sarif.Region;
import com.contrastsecurity.sarif.Replacement;
import com.contrastsecurity.sarif.Result;
import com.contrastsecurity.sarif.Run;
import com.contrastsecurity.sarif.SarifSchema210;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.build.api.review.SarifRefused;
import org.jspecify.annotations.Nullable;

/**
 * The one translator from a profile document from outside to Porelo's review records: read by
 * {@link SarifReading}, checked by {@link ProfileCheck} for its channel, then each result made a
 * {@link ReviewComment} or a {@link ForgeReply}.
 *
 * <p>The producer comes from the channel, never from the document: an adapter's items are its
 * forge's accounts, named by the fields the profile requires of a forge page, each item at the time
 * the forge says it was written; a task's submission is the task's, without an id, a thread or a
 * time. A refusal names a rule and a JSON Pointer, never the document's text.
 *
 * <p>An adapter's page also names its resolved threads: those every one of whose results on the
 * page says {@code resolved}. A task's submission names in {@code porelo/resolveThreads} the
 * threads it marks resolved: each becomes a {@link ThreadResolve} after the submission's results.
 *
 * <p>A task's submission on a push may hold one reject, read as its reason and no item; a second
 * refuses the whole submission.
 */
public final class ProfileTranslator implements ProfileReading {

    @Override
    public ProfileRead read(final byte[] document, final ProfileChannel channel)
            throws ProfileRefused {
        final SarifSchema210 sarif;
        try {
            sarif =
                    ProfileCheck.check(
                            switch (channel) {
                                case ProfileChannel.Adapter _ -> ProfileCheck.Channel.ADAPTER;
                                case ProfileChannel.FromTask _ -> ProfileCheck.Channel.TASK;
                            },
                            SarifReading.read(document));
        } catch (final SarifRefused e) {
            throw new ProfileRefused(rule(e));
        }
        final List<ProfileItem> items = new ArrayList<>();
        final Set<String> resolved = new HashSet<>();
        final Set<String> open = new HashSet<>();
        Completeness completeness = Completeness.COMPLETE;
        Body reject = null;
        final List<String> named = new ArrayList<>();
        final List<Run> runs = sarif.getRuns();
        for (int i = 0; i < runs.size(); i++) {
            final Run run = runs.get(i);
            if (channel instanceof ProfileChannel.Adapter) {
                completeness = completeness(bag(run.getProperties()).get("porelo/completeness"));
            }
            final List<Result> results = run.getResults();
            for (int j = 0; j < results.size(); j++) {
                if (channel instanceof ProfileChannel.Adapter) {
                    state(results.get(j), resolved, open);
                }
                if (bag(results.get(j).getProperties()).containsKey(ProfileCheck.JUDGMENT)) {
                    // The check let it through only in a task's submission on a push; one is all
                    // a submission may judge.
                    if (reject != null) {
                        throw new ProfileRefused(
                                "submission: more than one reject at /runs/" + i + "/results/" + j);
                    }
                    reject =
                            new Body(
                                    ProfileCheck.textAsGiven(
                                            results.get(j).getMessage().getText()));
                    continue;
                }
                try {
                    items.add(item(results.get(j), channel));
                } catch (final RuntimeException e) {
                    // A record's own bounds or a field the check let pass: only a rule leaves here.
                    throw new ProfileRefused(
                            "record: "
                                    + recordOf(results.get(j), channel)
                                    + " at /runs/"
                                    + i
                                    + "/results/"
                                    + j);
                }
            }
            if (channel instanceof ProfileChannel.FromTask task) {
                resolves(run, task, i, items);
                final String place = named(bag(run.getProperties()));
                if (place != null) {
                    named.add(place);
                }
            }
        }
        // A thread one of whose results says open stays open, so no point is ever hidden.
        resolved.removeAll(open);
        return new ProfileRead(items, resolved, completeness, reject, named);
    }

    /**
     * The place a submission's run names, as {@link ProfileRead#named} writes it, or null when it
     * names none: the run's own place is then the one its items go to.
     */
    private static @Nullable String named(final Map<String, Object> properties) {
        final String pullRequest = text(properties, "porelo/pullRequest");
        if (pullRequest != null) {
            return "pullRequest:" + pullRequest;
        }
        final String project = text(properties, "porelo/project");
        final String repository = text(properties, "porelo/repository");
        final String push = text(properties, "porelo/push");
        if (project == null && repository == null && push == null) {
            return null;
        }
        return "push:"
                + (project == null ? "" : project)
                + "/"
                + (repository == null ? "" : repository)
                + "/"
                + (push == null ? "" : push);
    }

    /**
     * Takes a submission's resolved threads as resolve items after its results, each stamped with
     * the task. Only a submission carries them; the schema refuses them on a page.
     */
    private static void resolves(
            final Run run,
            final ProfileChannel.FromTask task,
            final int i,
            final List<ProfileItem> items)
            throws ProfileRefused {
        final Object threads = bag(run.getProperties()).get("porelo/resolveThreads");
        if (threads == null) {
            return;
        }
        if (!(threads instanceof List<?> named)) {
            // Not reached by a checked submission: its schema holds the field to a list.
            throw new ProfileRefused("record: resolve at /runs/" + i + "/properties");
        }
        for (int k = 0; k < named.size(); k++) {
            try {
                items.add(
                        new ProfileItem(
                                null,
                                null,
                                task.task(),
                                new ThreadResolve(named.get(k) instanceof String id ? id : ""),
                                null));
            } catch (final IllegalArgumentException e) {
                throw new ProfileRefused(
                        "record: resolve at /runs/"
                                + i
                                + "/properties/porelo~1resolveThreads/"
                                + k);
            }
        }
    }

    /**
     * Takes a forge result's thread into the threads it calls resolved or the ones it calls open.
     */
    private static void state(
            final Result result, final Set<String> resolved, final Set<String> open) {
        final Map<String, Object> properties = bag(result.getProperties());
        String thread = text(properties, "porelo/threadId");
        if (thread == null) {
            thread = text(properties, "porelo/replyTo");
        }
        if (thread == null) {
            // Not reached by a checked page: its schema requires porelo/threadId on every result.
            return;
        }
        if ("resolved".equals(properties.get("porelo/threadState"))) {
            resolved.add(thread);
        } else {
            open.add(thread);
        }
    }

    private static ProfileItem item(final Result result, final ProfileChannel channel) {
        final Map<String, Object> properties = bag(result.getProperties());
        final Body body = new Body(ProfileCheck.textAsGiven(result.getMessage().getText()));
        final String replyTo = text(properties, "porelo/replyTo");
        final ReviewItem item;
        if (replyTo != null) {
            item = new ForgeReply(replyTo, body);
        } else {
            final PhysicalLocation location = result.getLocations().get(0).getPhysicalLocation();
            final Region region = location.getRegion();
            item =
                    new ReviewComment(
                            new LineAnchor(
                                    ProfileCheck.pathOf(location.getArtifactLocation().getUri()),
                                    Side.named(String.valueOf(properties.get("porelo/diffSide"))),
                                    region.getStartLine(),
                                    region.getEndLine()),
                            body,
                            suggestion(result.getFixes()));
        }
        return switch (channel) {
            case ProfileChannel.Adapter adapter ->
                    new ProfileItem(
                            result.getGuid(),
                            text(properties, "porelo/threadId"),
                            new ForgeAccount(
                                    adapter.forge(),
                                    String.valueOf(properties.get("porelo/forgeAccountId")),
                                    String.valueOf(properties.get("porelo/forgeAccountName"))),
                            item,
                            // The check requires it on a forge page and holds it to a real time.
                            Instant.parse(String.valueOf(properties.get("porelo/createdAt"))));
            case ProfileChannel.FromTask task ->
                    new ProfileItem(null, null, task.task(), item, null);
        };
    }

    /** The one suggestion a comment's single fix carries, as the profile allows it. */
    private static @Nullable Suggestion suggestion(final @Nullable Set<Fix> fixes) {
        if (fixes == null) {
            return null;
        }
        for (final Fix fix : fixes) {
            for (final ArtifactChange change : fix.getArtifactChanges()) {
                for (final Replacement replacement : change.getReplacements()) {
                    return new Suggestion(replacement.getInsertedContent().getText());
                }
            }
        }
        return null;
    }

    private static Completeness completeness(final @Nullable Object word) {
        for (final Completeness completeness : Completeness.values()) {
            if (completeness.word().equals(word)) {
                return completeness;
            }
        }
        // The schema requires one of the four words on a page; none is no evidence of any.
        return Completeness.UNKNOWN;
    }

    /** Names the record a result's fields failed to make, by what the result is. */
    private static String recordOf(final Result result, final ProfileChannel channel) {
        if (channel instanceof ProfileChannel.Adapter) {
            return "forge account";
        }
        return text(bag(result.getProperties()), "porelo/replyTo") != null ? "reply" : "comment";
    }

    /**
     * The one table from a reader's or a check's rule to a profile refusal's rule word: the reading
     * rules, the schema, the channel and the profile's own points, each under its own prefix.
     */
    static String rule(final SarifRefused refused) {
        final String at = refused.path();
        return switch (refused.rule()) {
            case DOCUMENT_SIZE,
                    ENCODING,
                    JSON_SYNTAX,
                    NESTING,
                    TOKENS,
                    STRING_LENGTH,
                    NAME_LENGTH,
                    NUMBER_LENGTH,
                    DUPLICATE_KEY,
                    TRAILING_TOKENS,
                    RESULTS,
                    RESULT_SIZE,
                    PROFILE_VERSION,
                    URI_LENGTH,
                    BINDING ->
                    "reading: " + refused.rule().text() + " at " + at;
            case SCHEMA -> "schema: " + at;
            case CHANNEL -> "channel: " + at;
            case MESSAGE_ESCAPE,
                    TEXT_SIZE,
                    CONTROL_CHARACTER,
                    FORMAT_CHARACTER,
                    SEPARATOR_CHARACTER,
                    LONE_SURROGATE,
                    ARTIFACT_URI,
                    URL_SHAPE,
                    REGION,
                    FIX_ANCHOR,
                    REPLACEMENT_SIZE,
                    REPLACEMENT_LINES,
                    REPLACEMENT_LINE_LENGTH,
                    CREATED_AT ->
                    "profile: " + refused.rule().text() + " at " + at;
        };
    }

    private static @Nullable String text(final Map<String, Object> properties, final String key) {
        return properties.get(key) instanceof String value ? value : null;
    }

    private static Map<String, Object> bag(final @Nullable PropertyBag bag) {
        return bag == null ? Map.of() : bag.getAdditionalProperties();
    }
}
