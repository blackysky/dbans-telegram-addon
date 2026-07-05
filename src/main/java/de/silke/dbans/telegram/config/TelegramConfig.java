package de.silke.dbans.telegram.config;

import de.silke.dbans.telegram.locale.SupportedLocale;
import lombok.Getter;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@Getter
public class TelegramConfig {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final String token;
    private final List<String> chatIds;
    private final SupportedLocale locale;
    private final ZoneId timezone;
    private final boolean notifyOnCreate;
    private final boolean notifyOnRevoke;
    private final boolean notifyOnModify;
    private final boolean notifyOnExpire;
    private final Set<PunishmentType> ignoredTypes;

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
        this.ignoredTypes = parseIgnoredTypes(config.getStringList("notifications.ignored-types"));
    }

    private static @NotNull Set<PunishmentType> parseIgnoredTypes(@NotNull List<String> raw) {
        Set<PunishmentType> types = EnumSet.noneOf(PunishmentType.class);
        for (String name : raw) {
            try {
                types.add(PunishmentType.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown punishment type in notifications.ignored-types: " + name);
            }
        }
        return types;
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

    public @NotNull List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (token.isBlank()) {
            errors.add("client.token is not configured");
        }
        if (chatIds.isEmpty()) {
            errors.add("client.chat-ids is empty");
        } else {
            List<String> invalidIds = chatIds.stream().filter(id -> !isValidChatId(id)).toList();
            if (!invalidIds.isEmpty()) {
                errors.add("client.chat-ids contains invalid entries: " + String.join(", ", invalidIds));
            }
        }
        return errors;
    }

}