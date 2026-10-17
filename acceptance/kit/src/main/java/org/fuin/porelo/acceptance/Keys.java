package org.fuin.porelo.acceptance;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The keys a scenario can press, by name, as the bytes an {@code xterm-256color} sends for them.
 * <p>
 * What reaches a program behind the task's tmux is what the step asserts, so the table holds what the outside terminal
 * sends, never what tmux turns it into. Measured on 2026-10-09 with a program that logged every byte it received.
 */
final class Keys {

    private static final String ESC = "\u001b";

    private static final Map<String, String> TABLE = table();

    private Keys() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Map<String, String> table() {
        final Map<String, String> keys = new LinkedHashMap<>();
        keys.put("Esc", ESC);
        keys.put("PageUp", ESC + "[5~");
        keys.put("PageDown", ESC + "[6~");
        keys.put("Shift+PageUp", ESC + "[5;2~");
        keys.put("Shift+PageDown", ESC + "[6;2~");
        // The CSI u form a terminal with extended keys sends; xterm's own form is modifyOtherKeys'.
        keys.put("Shift+Enter", ESC + "[13;2u");
        keys.put("Shift+Enter, xterm's form", ESC + "[27;2;13~");
        keys.put("Ctrl+Right", ESC + "[1;5C");
        keys.put("Alt+x", ESC + "x");
        // Sent only to a program that asked for them with ESC [ ? 1004 h.
        keys.put("focus in", ESC + "[I");
        keys.put("focus out", ESC + "[O");
        return Map.copyOf(keys);
    }

    /**
     * Returns the bytes of a key.
     *
     * @param name The key, as the table names it.
     * @return Its bytes.
     * @throws IllegalArgumentException For a key the table does not have, naming the ones it has.
     */
    static byte[] bytes(final String name) {
        final String sent = TABLE.get(name);
        if (sent == null) {
            throw new IllegalArgumentException("No key '" + name + "'; the keys are " + String.join(", ",
                    new java.util.TreeSet<>(TABLE.keySet())));
        }
        return sent.getBytes(StandardCharsets.UTF_8);
    }
}
