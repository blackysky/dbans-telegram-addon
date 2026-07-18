package de.silke.dbans.telegram.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramConfigTest {

    private static FileConfiguration baseYaml() {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        return yaml;
    }

    @Test
    void invalidTimezone_warnsAndFallsBackToUtc() {
        FileConfiguration yaml = baseYaml();
        yaml.set("timezone", "Not/AZone");

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.getTimezone().getId()).isEqualTo("UTC");
        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("Invalid timezone"));
    }

    @Test
    void unknownLocale_warnsAndFallsBackToDefault() {
        FileConfiguration yaml = baseYaml();
        yaml.set("locale", "xx");

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("Unknown locale"));
    }

    @Test
    void unknownPunishmentType_warns() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.ignored-types", List.of("NOT_A_TYPE"));

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("Unknown punishment type"));
    }

    @Test
    void blankIgnoredTypeEntries_areIgnoredWithoutWarning() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.ignored-types", List.of("", "   "));

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.notifications().ignoredTypes()).isEmpty();
        assertThat(config.diagnostics().warnings()).isEmpty();
    }

    @Test
    void localeAndTimezone_areTrimmed() {
        FileConfiguration yaml = baseYaml();
        yaml.set("locale", " en ");
        yaml.set("timezone", " UTC ");

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.diagnostics().warnings()).isEmpty();
        assertThat(config.getTimezone().getId()).isEqualTo("UTC");
    }

    @Test
    void chatIds_areDeduplicated() {
        FileConfiguration yaml = baseYaml();
        yaml.set("client.chat-ids", List.of("123", "123", " 123", "456"));

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.getChatIds()).containsExactlyInAnyOrder("123", "456");
    }

}