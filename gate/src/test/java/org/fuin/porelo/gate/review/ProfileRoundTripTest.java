package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.gate.review.ProfileWritingTest.FACTS;
import static org.fuin.porelo.gate.review.ProfileWritingTest.FORGE;
import static org.fuin.porelo.gate.review.ProfileWritingTest.LOGIN;
import static org.fuin.porelo.gate.review.ProfileWritingTest.MONA;
import static org.fuin.porelo.gate.review.ProfileWritingTest.OCTOCAT;
import static org.fuin.porelo.gate.review.ProfileWritingTest.comment;
import static org.fuin.porelo.gate.review.ProfileWritingTest.reply;
import static org.fuin.porelo.gate.review.ProfileWritingTest.twoThreads;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the one writer writes in profile 2, the one translator reads back to the same items. */
class ProfileRoundTripTest {

    private static final ProfileChannel ADAPTER = new ProfileChannel.Adapter(FORGE);

    private static final Task TASK = new Task("shop-2");

    private static final String IMITATION =
            "\"}]}],\"runs\":[{\"results\":[]}]} \\[link](https://e.example) {0} \\u0022 [x]";

    private final ReviewSarif writer = new ReviewSarif();

    private final ProfileTranslator translator = new ProfileTranslator();

    @Test
    void pagesReadBackThroughTheAdapterChannelToTheSameItems() throws ProfileRefused {
        final Conversation conversation = twoThreads();
        final ProfileRead read = translator.read(writer.pages(FACTS, conversation).get(0), ADAPTER);
        assertThat(read.items())
                .as("the items read back")
                .containsExactlyElementsOf(items(conversation.items()));
        assertThat(read.completeness())
                .as("the completeness read back")
                .isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void aConversationOverSeveralPagesReadsBackWholeAndInOrder() throws ProfileRefused {
        final List<Kept> kept = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            final String thread = String.valueOf(5000 + i);
            kept.add(comment(thread, OCTOCAT, LOGIN, "Comment " + i + ".", null));
            if (i % 2 == 0) {
                kept.add(reply(thread, MONA, "Reply " + i + "."));
            }
        }
        final List<ProfileItem> read = new ArrayList<>();
        Completeness last = Completeness.UNKNOWN;
        for (final byte[] page :
                writer.pages(
                        FACTS,
                        new Conversation(kept, List.of(), Completeness.PARTIAL, "PagePartial"))) {
            final ProfileRead one = translator.read(page, ADAPTER);
            read.addAll(one.items());
            last = one.completeness();
        }
        assertThat(read).as("every page's items").containsExactlyElementsOf(items(kept));
        assertThat(last).as("the last page's completeness").isEqualTo(Completeness.PARTIAL);
    }

    @Test
    void oneItemReadsBackThroughTheAdapterChannelToTheSameItem() throws ProfileRefused {
        final Kept kept = twoThreads().items().get(0);
        assertThat(translator.read(writer.one(FACTS, kept, kept), ADAPTER).items())
                .as("the one item read back")
                .containsExactlyElementsOf(items(List.of(kept)));
    }

    @Test
    void aSubmissionReadsBackThroughTheTaskChannelToTheSameItem() throws ProfileRefused {
        final ReviewComment comment =
                new ReviewComment(LOGIN, new Body("Check first."), new Suggestion("x;\n"));
        assertThat(
                        translator
                                .read(
                                        writer.submission(FACTS, comment, LOGIN),
                                        new ProfileChannel.FromTask(TASK))
                                .items())
                .as("the submitted item read back")
                .containsExactly(new ProfileItem(null, null, TASK, comment, null));
    }

    @Test
    void oneReplyAndASubmittedReplyReadBackToTheSameReply() throws ProfileRefused {
        final Conversation conversation = twoThreads();
        final Kept reply = conversation.items().get(1);
        assertThat(
                        translator
                                .read(
                                        writer.one(FACTS, reply, conversation.items().get(0)),
                                        ADAPTER)
                                .items())
                .as("one reply read back")
                .containsExactlyElementsOf(items(List.of(reply)));
        final ForgeReply submitted = new ForgeReply("1834527690", new Body("Done {now}."));
        assertThat(
                        translator
                                .read(
                                        writer.submission(FACTS, submitted, LOGIN),
                                        new ProfileChannel.FromTask(TASK))
                                .items())
                .as("a submitted reply read back")
                .containsExactly(new ProfileItem(null, null, TASK, submitted, null));
    }

    @Test
    void aTextImitatingSarifOrItsMarkupStaysAString() throws ProfileRefused {
        final Kept kept =
                comment(
                        "77",
                        OCTOCAT,
                        new LineAnchor("docs/a b%[1].md", Side.NEW, 1, 1),
                        IMITATION,
                        IMITATION);
        assertThat(
                        translator
                                .read(
                                        writer.pages(
                                                        FACTS,
                                                        new Conversation(
                                                                List.of(kept),
                                                                List.of(),
                                                                Completeness.COMPLETE,
                                                                null))
                                                .get(0),
                                        ADAPTER)
                                .items())
                .as("an imitating text read back on a page")
                .containsExactlyElementsOf(items(List.of(kept)));
        assertThat(
                        translator
                                .read(
                                        writer.submission(
                                                FACTS,
                                                kept.item(),
                                                new LineAnchor("docs/a b%[1].md", Side.NEW, 1, 1)),
                                        new ProfileChannel.FromTask(TASK))
                                .items())
                .as("an imitating text read back from a submission")
                .containsExactly(new ProfileItem(null, null, TASK, kept.item(), null));
    }

    private static List<ProfileItem> items(final List<Kept> kept) {
        return kept.stream()
                .map(
                        item ->
                                new ProfileItem(
                                        item.guid(),
                                        item.thread(),
                                        item.origin().producer(),
                                        item.item(),
                                        null))
                .toList();
    }
}
