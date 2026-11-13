package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.CardException;
import org.junit.jupiter.api.Test;

/** The step a kept record waits at: read back only as a walk writes it. */
class TreeRecordWaitingTest {

    private static final String ADDRESS = "https://forge.example/acme/organization.git";

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static String written() {
        return new TreeRecord(
                        COMMIT,
                        TreeModel.empty(),
                        List.of(),
                        null,
                        new OrganizationQuestions.Waiting(
                                COMMIT, TreeWalk.FOUNDER_OWNS, "rate limited"))
                .toJson(ADDRESS);
    }

    @Test
    void aWaitingStepIsReadBackAsWritten() {
        assertThat(TreeRecord.read(written(), ADDRESS).waiting())
                .isEqualTo(
                        new OrganizationQuestions.Waiting(
                                COMMIT, TreeWalk.FOUNDER_OWNS, "rate limited"));
    }

    @Test
    void wordsAWalkNeverWritesRefuseTheRecord() {
        assertThatThrownBy(
                        () ->
                                TreeRecord.read(
                                        written().replace(TreeWalk.FOUNDER_OWNS, "anything else"),
                                        ADDRESS))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("waiting");
    }

    @Test
    void aKindTheDirectoryNeverSaysRefusesTheRecord() {
        assertThatThrownBy(
                        () ->
                                TreeRecord.read(
                                        written().replace("rate limited", "the forge's words"),
                                        ADDRESS))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("waiting");
    }

    @Test
    void aStepThatIsNoHashRefusesTheRecord() {
        final String text = written();
        final int step = text.indexOf("\"waiting\"");
        final String broken =
                text.substring(0, step) + text.substring(step).replaceFirst(COMMIT, "HEAD");
        assertThatThrownBy(() -> TreeRecord.read(broken, ADDRESS))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("waiting");
    }

    @Test
    void aFieldTheReaderDoesNotKnowIsLeftAlone() {
        // As an older build reads a record with a waiting step: by the fields it knows alone.
        final String later = written().replaceFirst("\\{", "{\"later\":{\"field\":1},");
        assertThat(TreeRecord.read(later, ADDRESS).commit()).isEqualTo(COMMIT);
    }
}
