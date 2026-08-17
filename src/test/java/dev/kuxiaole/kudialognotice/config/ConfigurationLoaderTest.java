package dev.kuxiaole.kudialognotice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationLoaderTest {
    private final YamlConfiguration yaml = new YamlConfiguration();

    @Test
    void acceptsMiniMessageLinksAndConfiguredPlaceholders() {
        assertDoesNotThrow(() -> ConfigurationLoader.validateMiniMessage(
                yaml,
                "body[0]",
                "<green>Hello <player></green> "
                        + "<click:open_url:'https://example.com'><underlined>link</underlined></click>"));
    }

    @Test
    void rejectsMalformedMiniMessageBeforeReload() {
        assertThrows(ConfigurationException.class, () -> ConfigurationLoader.validateMiniMessage(
                yaml, "title", "<green><bold>missing closing tags"));
    }
}
