package de.silke.dbans.telegram.config;

import de.silke.dbans.telegram.locale.SupportedLocale;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.List;
import java.util.logging.Logger;

@Getter
public class TelegramConfig {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon.TelegramConfig");

    private final String token;
    private final List<String> chatIds;
    private final SupportedLocale locale;
    private final ZoneId timezone;
    private final boolean notifyOnCreate;
    private final boolean notifyOnRevoke;
    private final boolean notifyOnModify;
    private final boolean notifyOnExpire;

    public TelegramConfig(@NotNull FileConfiguration config) {
        this.token = config.getString("client.token", "").trim();
        this.chatIds = config.getStringList("client.chat-ids").stream()
                             .map(String::trim)
                             .filter(id -> !id.isBlank())
                             .toList();
        this.locale = SupportedLocale.fromCode(config.getString("locale", "en"));
        this.timezone = parseZone(config.getString("timezone", "UTC"));
        this.notifyOnCreate = config.getBoolean("notifications.on-create", true);
        this.notifyOnRevoke = config.getBoolean("notifications.on-revoke", true);
        this.notifyOnModify = config.getBoolean("notifications.on-modify", true);
        this.notifyOnExpire = config.getBoolean("notifications.on-expire", true);
    }

    private static @NotNull ZoneId parseZone(String id) {
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            log.warning("Invalid timezone '" + id + "', falling back to UTC");
            return ZoneId.of("UTC");
        }
    }

    private static boolean isValidChatId(@NotNull String id) {
        boolean valid;
        if (id.startsWith("@")) {
            valid = id.length() > 1;
        } else {
            try {
                Long.parseLong(id);
                valid = true;
            } catch (NumberFormatException e) {
                valid = false;
            }
        }
        return valid;
    }

    public boolean isValid() {
        return !token.isBlank()
               && !chatIds.isEmpty()
               && chatIds.stream().allMatch(TelegramConfig::isValidChatId);
    }
}
