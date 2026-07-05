package de.silke.dbans.telegram.application;

import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.locale.MessageProvider;
import de.silke.dbans.telegram.locale.SupportedLocale;
import me.demro.dlibs.dbans.api.event.*;
import me.demro.dlibs.dbans.api.punishment.Punishment;
import me.demro.dlibs.dbans.api.punishment.PunishmentIssuer;
import me.demro.dlibs.dbans.api.punishment.PunishmentReason;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private TelegramClient mockClient;
    private NotificationService service;

    private static Punishment stubPunishment(PunishmentType type, String player, String server) {
        Punishment punishment = mock(Punishment.class);
        when(punishment.type()).thenReturn(type);
        when(punishment.targetName()).thenReturn(player);
        when(punishment.serverName()).thenReturn(server);
        return punishment;
    }

    @BeforeEach
    void setUp() {
        mockClient = mock(TelegramClient.class);

        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"), "dbans-test"));
        when(plugin.getResource(anyString())).thenAnswer(inv ->
                                                                 NotificationServiceTest.class.getClassLoader().getResourceAsStream(inv.getArgument(0)));

        MessageProvider messageProvider = new MessageProvider(plugin, SupportedLocale.EN);
        service = new NotificationService(mockClient, messageProvider, ZoneId.of("UTC"));
    }

    private String captureMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockClient).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test
    void notifyCreate_permanentBan() {
        Punishment punishment = stubPunishment(PunishmentType.BAN, "TestSilke", "survival");
        when(punishment.issuer()).thenReturn(PunishmentIssuer.console());
        when(punishment.reason()).thenReturn(PunishmentReason.of("Cheating"));
        when(punishment.expiresAt()).thenReturn(Optional.empty());

        service.notify(new PunishmentCreateEvent(punishment, EventOrigin.COMMAND, Instant.now(), false));

        String message = captureMessage();
        assertThat(message).contains("Ban", "TestSilke", "survival", "CONSOLE", "Cheating", "permanent");
    }

    @Test
    void notifyCreate_temporaryBan_formatsExpiry() {
        Punishment punishment = stubPunishment(PunishmentType.BAN, "TestSilke", "survival");
        when(punishment.issuer()).thenReturn(PunishmentIssuer.player(UUID.randomUUID(), "Admin"));
        when(punishment.reason()).thenReturn(PunishmentReason.of("Spamming"));
        when(punishment.expiresAt()).thenReturn(Optional.of(Instant.parse("2025-06-01T12:00:00Z")));

        service.notify(new PunishmentCreateEvent(punishment, EventOrigin.COMMAND, Instant.now(), false));

        String message = captureMessage();
        assertThat(message).contains("2025-06-01", "12:00", "UTC");
        assertThat(message).doesNotContain("permanent");
    }

    @Test
    void notifyRevoke_includesRevokerAndReason() {
        Punishment punishment = stubPunishment(PunishmentType.MUTE, "GoodSilke", "lobby");

        PunishmentRevocation revocation = new PunishmentRevocation(
                PunishmentIssuer.player(UUID.randomUUID(), "Mod"),
                PunishmentReason.of("Not fair"),
                "lobby",
                Instant.now()
        );

        service.notify(new PunishmentRevokeEvent(punishment, revocation, EventOrigin.COMMAND, Instant.now(), false));

        String message = captureMessage();
        assertThat(message).contains("Mute", "GoodSilke", "lobby", "Mod", "Not fair");
    }

    @Test
    void notifyModify_permanentToTemporary() {
        Punishment punishment = stubPunishment(PunishmentType.WARNING, "BannedSilke", "pvp");

        service.notify(new PunishmentModifyEvent(
                punishment,
                PunishmentReason.of("Small"),
                PunishmentReason.of("Big"),
                null,
                Instant.parse("2025-12-31T00:00:00Z"),
                EventOrigin.COMMAND,
                Instant.now(),
                false
        ));

        String message = captureMessage();
        assertThat(message).contains("Warning", "BannedSilke", "pvp",
                                     "Small", "Big", "permanent", "2025-12-31");
    }

    @Test
    void notifyExpire_containsTypePlayerServer() {
        Punishment punishment = stubPunishment(PunishmentType.JAIL, "JailedSilke", "creative");

        service.notify(new PunishmentExpireEvent(punishment, EventOrigin.AUTO, Instant.now(), true));

        String message = captureMessage();
        assertThat(message).contains("Jail", "JailedSilke", "creative");
    }
}
