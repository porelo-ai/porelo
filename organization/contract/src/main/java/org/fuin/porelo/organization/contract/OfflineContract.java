package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.fuin.porelo.organization.OrganizationException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What a host's offline check must guarantee against the key its admin was pinned by: a copy only
 * that pin vouches for, in which nobody changed what they were not allowed to, and which continues
 * what the host applied last. An adapter's test extends this and gives its fixture.
 */
public abstract class OfflineContract {

    private static final String OWNER = "mia";

    private static final String ADMIN = "hank";

    private static final String STRANGER = "mallory";

    private static final String LATER = "carol";

    private static final String HOST = "build";

    private static final String PROJECT = "demo";

    /**
     * Returns a new fixture, for one check alone.
     *
     * @return The fixture.
     * @throws Exception When it cannot be made.
     */
    protected abstract OfflineFixture fixture() throws Exception;

    /**
     * Runs every check against the adapter.
     *
     * @return One test per check.
     */
    @TestFactory
    Stream<DynamicTest> everyCheckHoldsForThisAdapter() {
        return checks().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> c.body().run(fixture())));
    }

    /**
     * Returns the checks.
     *
     * @return Every check, by name.
     */
    public static List<Check<OfflineFixture>> checks() {
        return List.of(
                new Check<>("thePinnedKeyAnswersTheHostsState", OfflineContract::pinned),
                new Check<>("anotherPersonsKeyPinnedIsRefused", OfflineContract::anotherPin),
                new Check<>("anUnsignedHostChangeIsRefused", s -> unsigned(s, false)),
                new Check<>("aStrangersHostChangeIsRefused", s -> stranger(s, false)),
                new Check<>("anUnsignedHostChangeUnderALaterOneIsRefused", s -> unsigned(s, true)),
                new Check<>("aStrangersHostChangeUnderALaterOneIsRefused", s -> stranger(s, true)),
                new Check<>("anOlderStateThanTheOneAppliedIsRefused", OfflineContract::older),
                new Check<>("aRewrittenHistoryIsRefused", OfflineContract::rewritten));
    }

    private static void arranged(final OfflineFixture store) throws IOException {
        store.hostWithAdmin(OWNER, HOST, ADMIN, PROJECT);
    }

    private static void bury(final OfflineFixture store, final boolean buried) throws IOException {
        if (buried) {
            store.fileCard(LATER);
        }
    }

    private static Map<String, Object> answered(final OfflineFixture store, final String since)
            throws IOException {
        try {
            return store.copy().check(HOST, store.fingerprint(ADMIN), since);
        } catch (final OrganizationException refused) {
            throw new AssertionError("the pinned key's own copy was refused", refused);
        }
    }

    private static void refused(final OfflineFixture store, final String pin, final String since) {
        assertThatThrownBy(() -> store.copy().check(HOST, pin, since))
                .as("the host applies nothing this copy says")
                .isInstanceOf(OrganizationException.class);
    }

    private static void pinned(final OfflineFixture store) throws Exception {
        arranged(store);

        final Map<String, Object> answer = answered(store, "");

        assertThat(String.valueOf(answer.get("commit")))
                .as("the revision it rests on")
                .isNotBlank();
        assertThat(answer.get("admins"))
                .as("the host's admin, pinned by the key that was pinned")
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry(ADMIN, store.fingerprint(ADMIN));
        assertThat(answer.get("host_card"))
                .as("what the host's card says")
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .isNotEmpty();
        assertThat(answer.get("projects"))
                .as("the project the host carries")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(project -> assertThat(project.get("name")).isEqualTo(PROJECT));
        assertThat(answer.get("persons"))
                .as("the host's admin, not revoked")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(
                        person -> {
                            assertThat(person.get("login")).isEqualTo(ADMIN);
                            assertThat(person.get("revoked")).isEqualTo(false);
                        });
    }

    private static void anotherPin(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(STRANGER);

        refused(store, store.fingerprint(STRANGER), "");
    }

    private static void unsigned(final OfflineFixture store, final boolean buried)
            throws Exception {
        arranged(store);
        store.hostChangeNobodyAllowed(null, HOST);
        bury(store, buried);

        refused(store, store.fingerprint(ADMIN), "");
    }

    private static void stranger(final OfflineFixture store, final boolean buried)
            throws Exception {
        arranged(store);
        store.fileCard(STRANGER);
        store.hostChangeNobodyAllowed(STRANGER, HOST);
        bury(store, buried);

        refused(store, store.fingerprint(ADMIN), "");
    }

    private static void older(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(LATER);
        final String applied = String.valueOf(answered(store, "").get("commit"));
        store.serveOlderState();

        refused(store, store.fingerprint(ADMIN), applied);
    }

    private static void rewritten(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(LATER);
        final String applied = String.valueOf(answered(store, "").get("commit"));
        store.rewriteHistory();

        refused(store, store.fingerprint(ADMIN), applied);
    }
}
