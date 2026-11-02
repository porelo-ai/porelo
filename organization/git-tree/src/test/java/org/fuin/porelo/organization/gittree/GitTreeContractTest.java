package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.organization.contract.OrganizationContract;
import org.fuin.porelo.organization.contract.OrganizationFixture;
import org.junit.jupiter.api.io.TempDir;

/** The adapter of the new structure holds to what any organization's store must guarantee. */
class GitTreeContractTest extends OrganizationContract {

    @TempDir Path dir;

    private int made;

    @Override
    protected OrganizationFixture fixture() throws IOException {
        return new GitTreeFixture(Files.createDirectory(dir.resolve("check-" + made++)));
    }
}
