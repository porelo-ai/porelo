package org.fuin.porelo.app;

import picocli.CommandLine.IFactory;

/**
 * Creates command objects with a {@link PoreloContext} already set.
 *
 * <p>Picocli instantiates subcommands itself, so this is where the context is handed to them.
 */
public class PoreloFactory implements IFactory {

    private final IFactory delegate = picocli.CommandLine.defaultFactory();

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context Context to give to the commands.
     */
    public PoreloFactory(PoreloContext context) {
        this.context = context;
    }

    @Override
    public <K> K create(Class<K> cls) throws Exception {
        final K instance = delegate.create(cls);
        if (instance instanceof ContextAware aware) {
            aware.setContext(context);
        }
        return instance;
    }

    /** Implemented by commands that need the context. */
    public interface ContextAware {

        /**
         * Sets the context.
         *
         * @param context Context.
         */
        void setContext(PoreloContext context);
    }
}
