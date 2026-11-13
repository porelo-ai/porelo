package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.RootOwnedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HostForgeSettingsTest {
    private static final RootOwnedFile.Attributes ROOTS =
            path ->
                    Map.of(
                            "uid",
                            0,
                            "mode",
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? 0040755 : 0100644);
    private static final String VALID =
            "name=forge.example.org\naccount=porelo-forge\ngit_port=3001\n";

    @TempDir Path dir;

    private HostForgeSettings read(String text) throws Exception {
        final Path file = dir.resolve("forge.conf");
        Files.writeString(file, text);
        return HostForgeSettings.read(file, ROOTS);
    }

    @Test
    void aValidFileNamesTheForgeItsEntriesAndItsPortAsANumber() throws Exception {
        final HostForgeSettings settings = read(VALID);
        assertThat(settings).isNotNull();
        assertThat(settings.port()).isEqualTo(3001);
        assertThat(settings.personEntry()).isEqualTo("forge.example.org.person");
        assertThat(settings.centralEntry()).isEqualTo("forge.example.org.central");
        assertThat(HostForgeSettings.refusal(settings, dir.resolve("forge.conf"), true)).isNull();
    }

    @Test
    void aFileRootDoesNotAloneControlIsRefused() throws Exception {
        final Path file = dir.resolve("forge.conf");
        Files.writeString(file, VALID);
        final HostForgeSettings settings =
                HostForgeSettings.read(
                        file,
                        path ->
                                path.equals(file)
                                        ? Map.of("uid", 1000, "mode", 0100644)
                                        : ROOTS.read(path));
        assertThat(settings).isNull();
        assertThat(HostForgeSettings.refusal(settings, file, false))
                .isEqualTo(HostForgeSettings.NOT_ROOTS);
    }

    // Each check push and pull made with a reader of their own, in its words and its order.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "account=porelo-forge\\n| the forge settings name no single valid forge account",
                "account=Bad\\n| the forge settings name no single valid forge account",
                "name=INVALID\\n| the forge settings name no valid forge",
                "git_port=0\\n| names no valid git_port",
                "git_port=65536\\n| names no valid git_port",
                "git_port=abc\\n| names no valid git_port",
                "git_port=3001\\n| names no valid git_port",
                "name=INVALID\\naccount=x\\n| the forge settings name no single valid forge account"
            })
    void eachRefusalHasItsWords(String change, String words) throws Exception {
        final HostForgeSettings settings = read(VALID + change.replace("\\n", "\n"));
        final Path file = dir.resolve("forge.conf");
        assertThat(HostForgeSettings.refusal(settings, file, true)).endsWith(words);
    }

    @Test
    void theTokenCommandDoesNotNeedThePort() throws Exception {
        final HostForgeSettings settings = read("name=forge.example.org\naccount=porelo-forge\n");
        assertThat(settings.validPort()).isFalse();
        assertThat(HostForgeSettings.refusal(settings, dir.resolve("forge.conf"), false)).isNull();
        assertThat(HostForgeSettings.refusal(settings, dir.resolve("forge.conf"), true))
                .isEqualTo(dir.resolve("forge.conf") + " names no valid git_port");
    }
}
