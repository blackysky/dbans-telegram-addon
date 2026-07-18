package de.silke.dbans.telegram.lifecycle;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.config.TelegramConfig;
import me.demro.dlibs.dbans.api.event.*;
import me.demro.dlibs.dbans.api.punishment.Punishment;
import me.demro.dlibs.dbans.api.punishment.PunishmentIssuer;
import me.demro.dlibs.dbans.api.punishment.PunishmentReason;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AddonRuntimeTest {

    private static FileConfiguration baseYaml() {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        return yaml;
    }

    private static Punishment punishmentOf(PunishmentType type) {
        Punishment punishment = mock(Punishment.class);
        when(punishment.type()).thenReturn(type);
        when(punishment.targetName()).thenReturn("Someone");
        when(punishment.serverName()).thenReturn("survival");
        return punishment;
    }

    private static PunishmentCreateEvent createEvent(PunishmentType type) {
        Punishment punishment = punishmentOf(type);
        when(punishment.issuer()).thenReturn(PunishmentIssuer.console());
        when(punishment.reason()).thenReturn(PunishmentReason.of("reason"));
        return new PunishmentCreateEvent(punishment, EventOrigin.COMMAND, Instant.now(), false);
    }

    private static PunishmentRevokeEvent revokeEvent(PunishmentType type) {
        Punishment punishment = punishmentOf(type);
        PunishmentRevocation revocation = new PunishmentRevocation(
                PunishmentIssuer.console(), PunishmentReason.of("reason"), "survival", Instant.now());
        return new PunishmentRevokeEvent(punishment, revocation, EventOrigin.COMMAND, Instant.now(), false);
    }

    private static PunishmentModifyEvent modifyEvent(PunishmentType type) {
        Punishment punishment = punishmentOf(type);
        return new PunishmentModifyEvent(punishment, PunishmentReason.of("old"), PunishmentReason.of("new"),
                                         null, null, EventOrigin.COMMAND, Instant.now(), false);
    }

    private static PunishmentExpireEvent expireEvent(PunishmentType type) {
        Punishment punishment = punishmentOf(type);
        return new PunishmentExpireEvent(punishment, EventOrigin.AUTO, Instant.now(), true);
    }

    @Test
    void handleCreate_whenEnabledAndNotIgnored_notifies() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.on-create", true);
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        PunishmentCreateEvent event = createEvent(PunishmentType.BAN);
        runtime.handle(event);

        verify(notificationService).notify(event);
    }

    @Test
    void handleCreate_whenDisabledInConfig_doesNotNotify() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.on-create", false);
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.handle(createEvent(PunishmentType.BAN));

        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCreate_whenTypeIgnored_doesNotNotify() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.ignored-types", List.of("BAN"));
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.handle(createEvent(PunishmentType.BAN));

        verifyNoInteractions(notificationService);
    }

    @Test
    void handleRevoke_whenEnabledAndNotIgnored_notifies() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.on-revoke", true);
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        PunishmentRevokeEvent event = revokeEvent(PunishmentType.MUTE);
        runtime.handle(event);

        verify(notificationService).notify(event);
    }

    @Test
    void handleRevoke_whenDisabled_doesNotNotify() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.on-revoke", false);
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.handle(revokeEvent(PunishmentType.MUTE));

        verifyNoInteractions(notificationService);
    }

    @Test
    void handleModify_whenEnabledAndNotIgnored_notifies() {
        FileConfiguration yaml = baseYaml();
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        PunishmentModifyEvent event = modifyEvent(PunishmentType.WARNING);
        runtime.handle(event);

        verify(notificationService).notify(event);
    }

    @Test
    void handleModify_whenTypeIgnored_doesNotNotify() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.ignored-types", List.of("WARNING"));
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.handle(modifyEvent(PunishmentType.WARNING));

        verifyNoInteractions(notificationService);
    }

    @Test
    void handleExpire_whenEnabledAndNotIgnored_notifies() {
        FileConfiguration yaml = baseYaml();
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        PunishmentExpireEvent event = expireEvent(PunishmentType.JAIL);
        runtime.handle(event);

        verify(notificationService).notify(event);
    }

    @Test
    void handleExpire_whenDisabled_doesNotNotify() {
        FileConfiguration yaml = baseYaml();
        yaml.set("notifications.on-expire", false);
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.handle(expireEvent(PunishmentType.JAIL));

        verifyNoInteractions(notificationService);
    }

    @Test
    void handle_afterShutdown_doesNotAccessNotificationService() {
        FileConfiguration yaml = baseYaml();
        TelegramConfig config = new TelegramConfig(yaml);
        NotificationService notificationService = mock(NotificationService.class);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), notificationService);

        runtime.shutdown();
        runtime.handle(createEvent(PunishmentType.BAN));

        verifyNoInteractions(notificationService);
    }

    @Test
    void sendTestMessage_afterShutdown_failsWithoutTouchingClient() throws Exception {
        FileConfiguration yaml = baseYaml();
        TelegramConfig config = new TelegramConfig(yaml);
        TelegramClient client = mock(TelegramClient.class);
        AddonRuntime runtime = new AddonRuntime(config, client, mock(NotificationService.class));

        runtime.shutdown();
        CompletableFuture<Void> result = runtime.sendTestMessage("hello");

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .cause().isInstanceOf(IllegalStateException.class);
        verify(client, never()).sendMessage(any());
    }

    @Test
    void shutdown_calledTwice_closesUnderlyingClientOnce() {
        FileConfiguration yaml = baseYaml();
        TelegramConfig config = new TelegramConfig(yaml);
        TelegramClient client = mock(TelegramClient.class);
        AddonRuntime runtime = new AddonRuntime(config, client, mock(NotificationService.class));

        runtime.shutdown();
        runtime.shutdown();

        verify(client, times(1)).shutdown();
    }

    @Test
    void locale_reflectsConfig() {
        FileConfiguration yaml = baseYaml();
        yaml.set("locale", "ru");
        TelegramConfig config = new TelegramConfig(yaml);
        AddonRuntime runtime = new AddonRuntime(config, mock(TelegramClient.class), mock(NotificationService.class));

        assertThat(runtime.locale().getCode()).isEqualTo("ru");
    }

}