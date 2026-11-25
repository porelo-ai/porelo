package org.fuin.porelo.app;

import java.io.Serial;

/** No forge adapter of that name is installed; nothing was changed. */
public final class AdapterNotInstalled extends IllegalArgumentException {

    @Serial private static final long serialVersionUID = 1L;

    private final String adapter;

    /**
     * Constructor.
     *
     * @param adapter The name asked for.
     */
    public AdapterNotInstalled(final String adapter) {
        super("no forge adapter '" + adapter + "' is installed");
        this.adapter = adapter;
    }

    /**
     * Returns the name asked for.
     *
     * @return It.
     */
    public String adapter() {
        return adapter;
    }
}
