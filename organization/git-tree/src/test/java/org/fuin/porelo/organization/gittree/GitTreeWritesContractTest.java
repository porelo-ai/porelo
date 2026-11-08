package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.organization.contract.WritesContract;
import org.fuin.porelo.organization.contract.WritesFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/** The adapter's changes hold to what any organization's store must guarantee of them. */
// Thousands of git and ssh-keygen processes a case, as every read verifies the history again.
@Tag("slow")
class GitTreeWritesContractTest extends WritesContract {

    @TempDir Path dir;

    @Override
    protected WritesFixture fixture() throws IOException {
        return new GitTreeWritesFixture(Files.createTempDirectory(dir, "check-"));
    }
}
