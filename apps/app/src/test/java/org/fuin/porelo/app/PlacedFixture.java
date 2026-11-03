package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.fuin.porelo.wire.Directories;

/** An organization's project as a start would have placed it, for a test with no forge. */
final class PlacedFixture {

    private PlacedFixture() {}

    /**
     * A member's current key.
     *
     * @param login Whose it is.
     * @param key The key.
     * @return The row.
     */
    static OrganizationProjects.Key key(final String login, final String key) {
        return new OrganizationProjects.Key(
                login, "member", key, LocalDate.of(2000, 1, 1), LocalDate.of(2999, 1, 1), null);
    }

    /**
     * Records a project as placed, with nothing in its clone yet.
     *
     * @param context The machine.
     * @param name The project's name.
     * @param keys Its members' keys.
     * @throws IOException If it cannot be written.
     */
    static void record(
            final PoreloContext context, final String name, final OrganizationProjects.Key... keys)
            throws IOException {
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                name,
                                "c0",
                                "https://forge.example/" + name + ".git",
                                null,
                                List.of(keys),
                                null,
                                null,
                                null));
    }

    /**
     * Places a project: its record, and its file in the clone.
     *
     * @param context The machine.
     * @param name The project's name.
     * @param file What its file says.
     * @param keys Its members' keys.
     * @return The file.
     * @throws IOException If it cannot be written.
     */
    static Path place(
            final PoreloContext context,
            final String name,
            final String file,
            final OrganizationProjects.Key... keys)
            throws IOException {
        record(context, name, keys);
        final Path clone = context.paths().projects().organizationClone(name);
        Directories.create(clone);
        return Files.writeString(clone.resolve("project.yml"), file);
    }
}
