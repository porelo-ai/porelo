package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.organization.contract.OfflineContract;
import org.fuin.porelo.organization.contract.OfflineFixture;
import org.junit.jupiter.api.io.TempDir;

/** A host's reader of the new structure holds to what any offline check must guarantee. */
class GitTreeOfflineContractTest extends OfflineContract {

    @TempDir Path dir;

    private int made;

    @Override
    protected OfflineFixture fixture() throws IOException {
        return new GitTreeFixture(Files.createDirectory(dir.resolve("check-" + made++)));
    }
}
