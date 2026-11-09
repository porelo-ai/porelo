package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/** Supplies the build's filtered version in a JVM or a native image. */
public final class HostVersion implements IVersionProvider {
    /** Returns the version from the resource shipped in this program. */
    @Override
    public String[] getVersion() throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = HostVersion.class.getResourceAsStream("/porelo-version.properties")) {
            if (in == null) {
                throw new IOException("Missing porelo-version.properties");
            }
            properties.load(in);
        }
        return new String[] {"porelo-host " + properties.getProperty("version", "unknown")};
    }
}
