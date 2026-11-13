package org.fuin.porelo.shield;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link ResolvedNames}. */
class ResolvedNamesTest {

    /** Real lines, including the IPv6 answer that reached an operator as a bare number. */
    private static final String LOG =
            """
            dnsmasq[881150]: query[A] api.example.test from 127.0.0.1
            dnsmasq[881150]: reply api.example.test is 93.184.216.34
            dnsmasq[881150]: reply api.example.test is 2607:6bc0::10
            dnsmasq[881150]: cached other.example.test is 10.0.0.1
            """;

    @Test
    void namesTheAddressTheResolverAnswered() {
        assertThat(ResolvedNames.nameFor(LOG, "2607:6bc0::10")).contains("api.example.test");
        assertThat(ResolvedNames.nameFor(LOG, "93.184.216.34")).contains("api.example.test");
    }

    @Test
    void readsACachedAnswerToo() {

        // A repeat answer is served from cache and logged differently; the container was still told
        // that name, which is what the operator is being asked about.
        assertThat(ResolvedNames.nameFor(LOG, "10.0.0.1")).contains("other.example.test");
    }

    @Test
    void answersNothingForAnAddressNobodyResolved() {

        // The case that matters: an agent connecting to a literal address was never told a name,
        // and inventing one would be worse than showing the number.
        assertThat(ResolvedNames.nameFor(LOG, "8.8.8.8")).isEmpty();
        assertThat(ResolvedNames.nameFor("", "8.8.8.8")).isEmpty();
    }

    @Test
    void doesNotConfuseAnAddressThatIsAPrefixOfAnother() {

        // '10.0.0.1' must not match a line answering '10.0.0.11'.
        assertThat(
                        ResolvedNames.nameFor(
                                "dnsmasq[1]: reply longer.example.test is 10.0.0.11", "10.0.0.1"))
                .isEmpty();
    }

    @Test
    void namesTheNameAskedForWhenTheAnswerCameThroughACname() {
        // As dnsmasq logs a CNAME: the name asked for, then each name the chain passes, then the
        // addresses under the last one. The grant is for the name asked for.
        final String log =
                """
                dnsmasq[7]: query[A] fuinorg.jfrog.io from 10.88.0.2
                dnsmasq[7]: forwarded fuinorg.jfrog.io to 10.88.0.1
                dnsmasq[7]: reply fuinorg.jfrog.io is <CNAME>
                dnsmasq[7]: reply edge.jfrog.io is <CNAME>
                dnsmasq[7]: reply endpointdns-lb.jfrog.io is 34.1.2.3
                dnsmasq[7]: reply endpointdns-lb.jfrog.io is 34.1.2.4
                dnsmasq[7]: query[A] endpointdns-lb.jfrog.io from 10.88.0.2
                dnsmasq[7]: cached endpointdns-lb.jfrog.io is 34.1.2.5
                dnsmasq[7]: cached other.example.test is <CNAME>
                dnsmasq[7]: cached target.example.test is NODATA-IPv6
                dnsmasq[7]: cached target.example.test is 10.0.0.9
                """;

        assertThat(ResolvedNames.nameFor(log, "34.1.2.3")).contains("fuinorg.jfrog.io");
        assertThat(ResolvedNames.nameFor(log, "34.1.2.4"))
                .as("every address of the chain's answer")
                .contains("fuinorg.jfrog.io");
        assertThat(ResolvedNames.nameFor(log, "34.1.2.5"))
                .as("the target asked for by its own name is that name")
                .contains("endpointdns-lb.jfrog.io");
        assertThat(ResolvedNames.nameFor(log, "10.0.0.9"))
                .as("a cached chain too")
                .contains("other.example.test");
    }
}
