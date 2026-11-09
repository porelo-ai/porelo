package org.fuin.porelo.host;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets shutdown wait for in-flight registration and its rollback while the forge token is alive.
 */
final class ProjectCancellation implements AutoCloseable {
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final Thread shutdown;

    ProjectCancellation() {
        shutdown =
                Thread.ofPlatform()
                        .name("project-registration-shutdown")
                        .unstarted(this::cancelAndWait);
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    void check() {
        if (canceled.get()) {
            throw new HostRefusal(64, "input interrupted");
        }
    }

    void cancelAndWait() {
        canceled.set(true);
        // The owner holds this monitor through every mutation, publication, rollback and staging
        // cleanup.
        synchronized (this) {
            // Let the current request finish and record its identity before the owner rolls it
            // back.
        }
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException ex) {
            // Shutdown already started: its hook waits for the owner's monitor to be released.
        }
    }
}
