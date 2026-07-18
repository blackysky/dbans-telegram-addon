package de.silke.dbans.telegram.config;

import de.silke.dbans.telegram.locale.SupportedLocale;
import lombok.Getter;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Contract;
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
    private final NotificationSettings notifications;
    private final Diagnostics diagnostics;

    public TelegramConfig(@NotNull FileConfiguration config) {
        List<String> warnings = new ArrayList<>();

        this.token = config.getString("client.token", "").trim();
        this.chatIds = config.getStringList("client.chat-ids").stream()
                             .map(String::trim)
                             .filter(id -> !id.isBlank())
                             .distinct()
                             .toList();

        String localeCode = config.getString("locale", "en").trim();
        this.locale = SupportedLocale.fromCode(localeCode);
        if (!this.locale.getCode().equalsIgnoreCase(localeCode)) {
            warnings.add("Unknown locale '" + localeCode + "', falling back to " + this.locale.getCode());
        }

        this.timezone = parseZone(config.getString("timezone", "UTC").trim(), warnings);
        this.notifications = new NotificationSettings(
                config.getBoolean("notifications.on-create", true),
                config.getBoolean("notifications.on-revoke", true),
                config.getBoolean("notifications.on-modify", true),
                config.getBoolean("notifications.on-expire", true),
                parseIgnoredTypes(config.getStringList("notifications.ignored-types"), warnings)
        );
        this.diagnostics = new Diagnostics(warnings);
    }

    private static @NotNull Set<PunishmentType> parseIgnoredTypes(
            @NotNull List<String> raw, @NotNull List<String> warnings
    ) {
        Set<PunishmentType> types = EnumSet.noneOf(PunishmentType.class);
        for (String name : raw) {
            String trimmed = name.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                types.add(PunishmentType.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                String message = "Unknown punishment type in notifications.ignored-types: " + name;
                log.warning(message);
                warnings.add(message);
            }
        }
        return types;
    }

    private static @NotNull ZoneId parseZone(@NotNull String id, @NotNull List<String> warnings) {
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            String message = "Invalid timezone '" + id + "', falling back to UTC";
            log.warning(message);
            warnings.add(message);
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

    public @NotNull NotificationSettings notifications() {
        return notifications;
    }

    public @NotNull Diagnostics diagnostics() {
        return diagnostics;
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

    public record NotificationSettings(
            boolean notifyOnCreate,
            boolean notifyOnRevoke,
            boolean notifyOnModify,
            boolean notifyOnExpire,
            @NotNull Set<PunishmentType> ignoredTypes
    ) {

        @Contract(pure = true)
        public NotificationSettings {
            ignoredTypes = Set.copyOf(ignoredTypes);
        }

    }

    public record Diagnostics(@NotNull List<String> warnings) {

        @Contract(pure = true)
        public Diagnostics {
            warnings = List.copyOf(warnings);
        }

    }

}