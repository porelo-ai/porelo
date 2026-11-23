package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests that a forge's list is read as far as the forge itself says it goes. */
class ForgePagesTest {

    private static final String LINKS =
            "<http://127.0.0.1:3001/api/v1/repos/demo/demo/pulls?limit=50&page=2&state=open>;"
                    + " rel=\"next\",<http://127.0.0.1:3001/api/v1/repos/demo/demo/pulls?limit=50"
                    + "&page=3&state=open>; rel=\"last\"";

    @Test
    void aLinkNamingTheNextPageGoesOnAndOneWithoutItEnds() {
        // A Forgejo whose MAX_RESPONSE_ITEMS is below what was asked answers fewer on every page:
        // a short page is no end, only the forge's own word is.
        assertThat(ForgePages.after(Map.of("Link", List.of(LINKS)), 20))
                .as("a next page named")
                .isEqualTo(ForgePages.More.YES);
        assertThat(
                        ForgePages.after(
                                Map.of(
                                        "link",
                                        List.of(
                                                "<http://127.0.0.1:3001/x?page=1>; rel=\"first\","
                                                        + " <http://127.0.0.1:3001/x?page=2>;"
                                                        + " rel=\"prev\"")),
                                40))
                .as("the last page names none")
                .isEqualTo(ForgePages.More.NO);
    }

    @Test
    void aTotalCountEndsTheListOnceThatManyAreRead() {
        assertThat(ForgePages.after(Map.of("X-Total-Count", List.of("45")), 40))
                .as("fewer read than the forge holds")
                .isEqualTo(ForgePages.More.YES);
        assertThat(ForgePages.after(Map.of("x-total-count", List.of("45")), 45))
                .as("all of them read")
                .isEqualTo(ForgePages.More.NO);
    }

    @Test
    void aForgeThatSaysNothingIsReadToItsFirstEmptyPage() {
        assertThat(ForgePages.after(Map.of(), 20)).isEqualTo(ForgePages.More.UNSAID);
        assertThat(ForgePages.after(Map.of("X-Total-Count", List.of("many")), 20))
                .as("a count that is no number says nothing")
                .isEqualTo(ForgePages.More.UNSAID);
    }
}
