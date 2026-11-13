package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.contract.OfflineContract;
import org.fuin.porelo.organization.contract.OfflineFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A host's reader of the new structure holds to what any offline check must guarantee. */
class GitTreeOfflineContractTest extends OfflineContract {

    @TempDir Path dir;

    @Override
    protected OfflineFixture fixture() throws IOException {
        return new GitTreeFixture(Files.createTempDirectory(dir, "check-"));
    }

    // Losing a verified card's name makes a host create the person's room account unnamed.
    @Test
    void theOfflineResultCarriesTheVerifiedCardsChosenName() throws Exception {
        final var store = new GitTreeFixture(dir);
        store.hostWithAdmin("mia", "build", "hank", "demo");
        store.fileCardNamed("hank", "Hank, H=One");
        final var answer = store.copy().check("build", store.fingerprint("hank"), "");
        assertThat((List<?>) answer.get("persons"))
                .anySatisfy(
                        value -> {
                            final var person = (Map<?, ?>) value;
                            assertThat(person.get("login")).isEqualTo("hank");
                            assertThat(person.get("name")).isEqualTo("Hank, H=One");
                        });
    }
}
