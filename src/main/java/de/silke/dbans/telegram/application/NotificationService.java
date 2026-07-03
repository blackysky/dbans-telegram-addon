package de.silke.dbans.telegram.application;

import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.locale.MessageKey;
import de.silke.dbans.telegram.locale.MessageProvider;
import de.silke.dbans.telegram.model.PunishmentSnapshot;
import me.demro.dlibs.dbans.api.event.*;
import me.demro.dlibs.dbans.api.punishment.Punishment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class NotificationService {

    private final TelegramClient client;
    private final MessageProvider messageProvider;
    private final DateTimeFormatter dateFormat;

    public NotificationService(TelegramClient client, MessageProvider messageProvider, ZoneId timezone) {
        this.client = client;
        this.messageProvider = messageProvider;
        this.dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(timezone);
    }

    public void notify(@NotNull PunishmentCreateEvent event) {
        Punishment punishment = event.punishment();
        PunishmentSnapshot snapshot = PunishmentSnapshot.builder()
                                                        .type(punishment.type())
                                                        .playerName(punishment.targetName())
                                                        .serverName(punishment.serverName())
                                                        .createDetails(PunishmentSnapshot.CreateDetails.builder()
                                                                                                       .issuerName(punishment.issuer().name())
                                                                                                       .reason(punishment.reason().value())
                                                                                                       .expiresAt(punishment.expiresAt().orElse(null))
                                                                                                       .build())
                                                        .build();
        send(MessageKey.PUNISHMENT_CREATE, snapshot);
    }

    public void notify(@NotNull PunishmentRevokeEvent event) {
        Punishment punishment = event.punishment();
        PunishmentRevocation revocation = event.revocation();
        PunishmentSnapshot snapshot = PunishmentSnapshot.builder()
                                                        .type(punishment.type())
                                                        .playerName(punishment.targetName())
                                                        .serverName(punishment.serverName())
                                                        .revokeDetails(PunishmentSnapshot.RevokeDetails.builder()
                                                                                                       .revokerName(revocation.issuer().name())
                                                                                                       .revocationReason(revocation.reason().value())
                                                                                                       .build())
                                                        .build();
        send(MessageKey.PUNISHMENT_REVOKE, snapshot);
    }

    public void notify(@NotNull PunishmentModifyEvent event) {
        Punishment punishment = event.punishment();
        PunishmentSnapshot snapshot = PunishmentSnapshot.builder()
                                                        .type(punishment.type())
                                                        .playerName(punishment.targetName())
                                                        .serverName(punishment.serverName())
                                                        .modifyDetails(PunishmentSnapshot.ModifyDetails.builder()
                                                                                                       .oldReason(event.oldReason().value())
                                                                                                       .newReason(event.newReason().value())
                                                                                                       .oldExpiresAt(event.oldExpiresAt().orElse(null))
                                                                                                       .newExpiresAt(event.newExpiresAt().orElse(null))
                                                                                                       .build())
                                                        .build();
        send(MessageKey.PUNISHMENT_MODIFY, snapshot);
    }

    public void notify(@NotNull PunishmentExpireEvent event) {
        Punishment punishment = event.punishment();
        PunishmentSnapshot snapshot = PunishmentSnapshot.builder()
                                                        .type(punishment.type())
                                                        .playerName(punishment.targetName())
                                                        .serverName(punishment.serverName())
                                                        .build();
        send(MessageKey.PUNISHMENT_EXPIRE, snapshot);
    }

    private void send(@NotNull MessageKey key, @NotNull PunishmentSnapshot snapshot) {
        String message = messageProvider.format(key, toVars(snapshot));
        client.sendMessage(message);
    }

    private @NotNull Map<String, String> toVars(@NotNull PunishmentSnapshot snapshot) {
        Map<String, String> vars = new HashMap<>();
        vars.put("type", messageProvider.typeName(snapshot.getType().name()));
        vars.put("player", snapshot.getPlayerName());
        vars.put("server", snapshot.getServerName());

        PunishmentSnapshot.CreateDetails create = snapshot.getCreateDetails();
        if (create != null) {
            vars.put("issuer", create.getIssuerName());
            vars.put("reason", create.getReason());
            vars.put("expires", formatInstant(create.getExpiresAt()));
        }

        PunishmentSnapshot.RevokeDetails revoke = snapshot.getRevokeDetails();
        if (revoke != null) {
            vars.put("revoker", revoke.getRevokerName());
            vars.put("revocation_reason", revoke.getRevocationReason());
        }

        PunishmentSnapshot.ModifyDetails modify = snapshot.getModifyDetails();
        if (modify != null) {
            vars.put("old_reason", modify.getOldReason());
            vars.put("new_reason", modify.getNewReason());
            vars.put("old_expires", formatInstant(modify.getOldExpiresAt()));
            vars.put("new_expires", formatInstant(modify.getNewExpiresAt()));
        }

        return vars;
    }

    private @NotNull String formatInstant(@Nullable Instant instant) {
        return instant != null ? dateFormat.format(instant) : messageProvider.permanent();
    }
}
