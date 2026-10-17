package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds every place that pins the package repository's key to one fingerprint, and that one to the key the packages
 * are signed with.
 * <p>
 * <strong>The fingerprint is written down apart from the server the key comes from</strong>, so a changed key there is
 * refused. A rename could not change it: the key's address was renamed and the pin was not, and the setup script then
 * refused the very key the packages were signed with (found on 2026-10-17).
 */
@Tag("documents")
class PackageKeyPinnedTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    /** The key the packages are signed with: Porelo Packages, RSA 4096, made 2026-10-16. */
    private static final String KEY = "BF27A2FA637F505CAFBE431768D19D175D904415";

    /** The key the packages were signed with before the rename, retired with it. */
    private static final String RETIRED = "10EDAF73ECE5BB29A2E63E9C6B488A9326920DBE";

    private static final Pattern FINGERPRINT = Pattern.compile("\\b[0-9A-F]{40}\\b");

    private static final List<String> PINNING = List.of("dist/dist-setup/porelo-setup.sh", "README.md",
            "doc/getting-started.md");

    private static String read(final String path) throws IOException {
        return Files.readString(ROOT.resolve(path), StandardCharsets.UTF_8);
    }

    @Test
    void theSetupScriptPinsTheKeyThePackagesAreSignedWith() throws IOException {
        final Matcher pin = Pattern.compile("^KEY_FINGERPRINT=([0-9A-F]{40})$", Pattern.MULTILINE)
                .matcher(read("dist/dist-setup/porelo-setup.sh"));

        assertThat(pin.find()).as("the script's KEY_FINGERPRINT").isTrue();
        assertThat(pin.group(1)).isEqualTo(KEY);
    }

    @Test
    void everyPageThatInstallsTheKeyNamesTheSameFingerprintAndNoOther() throws IOException {
        for (final String path : PINNING) {
            final List<String> found = new ArrayList<>();
            final Matcher fingerprint = FINGERPRINT.matcher(read(path));
            while (fingerprint.find()) {
                found.add(fingerprint.group());
            }

            // A guard that finds nothing passes forever: each of these pins the key at least once.
            assertThat(found).as("fingerprints in %s", path).isNotEmpty().containsOnly(KEY).doesNotContain(RETIRED);
        }
    }
}
