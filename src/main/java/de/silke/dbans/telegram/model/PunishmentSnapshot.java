package de.silke.dbans.telegram.model;

import lombok.Builder;
import lombok.Value;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

@Value
@Builder
public class PunishmentSnapshot {

    @NotNull PunishmentType type;
    @NotNull String playerName;
    @NotNull String serverName;

    @Nullable CreateDetails createDetails;
    @Nullable RevokeDetails revokeDetails;
    @Nullable ModifyDetails modifyDetails;

    @Value
    @Builder
    public static class CreateDetails {

        @NotNull String issuerName;
        @NotNull String reason;
        @Nullable Instant expiresAt;
    }

    @Value
    @Builder
    public static class RevokeDetails {

        @NotNull String revokerName;
        @NotNull String revocationReason;
    }

    @Value
    @Builder
    public static class ModifyDetails {

        @NotNull String oldReason;
        @NotNull String newReason;
        @Nullable Instant oldExpiresAt;
        @Nullable Instant newExpiresAt;
    }
}
