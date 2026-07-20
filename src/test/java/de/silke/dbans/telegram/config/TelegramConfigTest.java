package de.silke.dbans.telegram.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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

    @Test
    void queueSettings_defaultToSafeValues() {
        TelegramConfig config = new TelegramConfig(baseYaml());

        assertThat(config.queue().capacity()).isEqualTo(100);
        assertThat(config.queue().overflowPolicy()).isEqualTo(QueueOverflowPolicy.DROP_NEWEST);
        assertThat(config.queue().shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.diagnostics().warnings()).isEmpty();
    }

    @Test
    void queueSettings_explicitValidValues_areParsed() {
        FileConfiguration yaml = baseYaml();
        yaml.set("queue.capacity", 5);
        yaml.set("queue.overflow-policy", "drop_newest");
        yaml.set("queue.shutdown-timeout-seconds", 30);

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.queue().capacity()).isEqualTo(5);
        assertThat(config.queue().overflowPolicy()).isEqualTo(QueueOverflowPolicy.DROP_NEWEST);
        assertThat(config.queue().shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.diagnostics().warnings()).isEmpty();
    }

    @Test
    void queueCapacity_zeroOrNegative_warnsAndFallsBackToDefault() {
        FileConfiguration yaml = baseYaml();
        yaml.set("queue.capacity", 0);

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.queue().capacity()).isEqualTo(100);
        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("queue.capacity"));
    }

    @Test
    void queueOverflowPolicy_unknown_warnsAndFallsBackToDropNewest() {
        FileConfiguration yaml = baseYaml();
        yaml.set("queue.overflow-policy", "DROP_OLDEST");

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.queue().overflowPolicy()).isEqualTo(QueueOverflowPolicy.DROP_NEWEST);
        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("queue.overflow-policy"));
    }

    @Test
    void queueShutdownTimeout_negative_warnsAndFallsBackToDefault() {
        FileConfiguration yaml = baseYaml();
        yaml.set("queue.shutdown-timeout-seconds", -1);

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.queue().shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.diagnostics().warnings()).anyMatch(w -> w.contains("queue.shutdown-timeout-seconds"));
    }

    @Test
    void queueShutdownTimeout_zero_isValidAndNotAWarning() {
        FileConfiguration yaml = baseYaml();
        yaml.set("queue.shutdown-timeout-seconds", 0);

        TelegramConfig config = new TelegramConfig(yaml);

        assertThat(config.queue().shutdownTimeout()).isEqualTo(Duration.ZERO);
        assertThat(config.diagnostics().warnings()).isEmpty();
    }
}