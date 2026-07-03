package de.silke.dbans.telegram.locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

@Getter
@RequiredArgsConstructor
public enum SupportedLocale {

    EN("en"),
    RU("ru"),
    DE("de");

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final String code;

    public static @NotNull SupportedLocale fromCode(@NotNull String code) {
        for (SupportedLocale locale : values()) {
            if (locale.code.equalsIgnoreCase(code)) {
                return locale;
            }
        }
        log.warning("Unknown locale '" + code + "', falling back to EN.");
        return EN;
    }
}
