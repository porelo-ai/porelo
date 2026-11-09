package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Keeps every host unit test's temporary files inside its checkout. */
public final class HostTemporaryDirectories implements TempDirFactory {
    /** Allocate beneath Maven's output directory, which exists before tests run. */
    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context)
            throws IOException {
        return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "host-test-");
    }
}
