package de.silke.dbans.telegram.locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MessageKey {

    PUNISHMENT_CREATE("punishment-create"),
    PUNISHMENT_REVOKE("punishment-revoke"),
    PUNISHMENT_MODIFY("punishment-modify"),
    PUNISHMENT_EXPIRE("punishment-expire");

    private final String yamlKey;
}
