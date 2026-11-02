package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.organization.contract.WritesContract;
import org.fuin.porelo.organization.contract.WritesFixture;
import org.junit.jupiter.api.io.TempDir;

/** The adapter's changes hold to what any organization's store must guarantee of them. */
class GitTreeWritesContractTest extends WritesContract {

    @TempDir Path dir;

    private int made;

    @Override
    protected WritesFixture fixture() throws IOException {
        return new GitTreeWritesFixture(Files.createDirectory(dir.resolve("check-" + made++)));
    }
}
