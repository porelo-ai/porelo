package org.fuin.porelo.wire.varlink;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * The daemon's contract, {@code org.fuin.porelo.Tasks1}, as porelo-wire carries it: the parts in
 * the order the list beside them names, put together into the one interface description the daemon
 * serves. A client outside porelo reads it here and gets the daemon's own text.
 */
public final class TasksContract {

    /** The interface's name. */
    public static final String INTERFACE = "org.fuin.porelo.Tasks1";

    private static final String FOLDER = "varlink/" + INTERFACE + "/";

    private TasksContract() {}

    /**
     * The interface description, put together from porelo-wire's parts.
     *
     * @return The IDL.
     * @throws IllegalStateException When a part or the list is not on the class path.
     */
    public static String description() {
        return description(classLoader());
    }

    /**
     * The parts in the order they are served, the header first.
     *
     * @return The parts' names, without {@code .varlink}.
     * @throws IllegalStateException When the list is not on the class path.
     */
    public static List<String> parts() {
        return parts(classLoader());
    }

    static String description(final ClassLoader loader) {
        final StringBuilder description = new StringBuilder();
        for (final String part : parts(loader)) {
            final String text = read(loader, FOLDER + part + ".varlink");
            description.append(text);
            if (!text.endsWith("\n")) {
                // Else the next part's first line would join this part's last.
                description.append('\n');
            }
        }
        return description.toString();
    }

    static List<String> parts(final ClassLoader loader) {
        final List<String> parts = new ArrayList<>();
        for (final String line : read(loader, FOLDER + "parts").lines().toList()) {
            final int comment = line.indexOf('#');
            final String part = (comment < 0 ? line : line.substring(0, comment)).strip();
            if (part.isEmpty()) {
                continue;
            }
            if (parts.contains(part)) {
                throw new IllegalStateException(
                        "The interface's parts name '" + part + "' twice: /" + FOLDER + "parts");
            }
            parts.add(part);
        }
        return List.copyOf(parts);
    }

    private static String read(final ClassLoader loader, final String name) {
        // The first copy on the class path is the one a reader gets; a second, from an older
        // porelo-wire or a module's classes from before the contract moved here, would be served
        // without anybody seeing which.
        try {
            final Enumeration<URL> copies = loader.getResources(name);
            if (copies.hasMoreElements()) {
                final URL first = copies.nextElement();
                if (copies.hasMoreElements()) {
                    throw new IllegalStateException(
                            "The interface description is on the class path more than once: /"
                                    + name
                                    + " in "
                                    + first
                                    + " and "
                                    + copies.nextElement());
                }
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot read the interface description: /" + name, ex);
        }
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(
                        "The interface description is not on the class path: /" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot read the interface description: /" + name, ex);
        }
    }

    private static ClassLoader classLoader() {
        final ClassLoader loader = TasksContract.class.getClassLoader();
        return loader == null ? ClassLoader.getSystemClassLoader() : loader;
    }
}
