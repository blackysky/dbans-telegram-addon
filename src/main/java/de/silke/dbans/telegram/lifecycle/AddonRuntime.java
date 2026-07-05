package de.silke.dbans.telegram.lifecycle;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.config.TelegramConfig;
import de.silke.dbans.telegram.locale.SupportedLocale;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.event.PunishmentExpireEvent;
import me.demro.dlibs.dbans.api.event.PunishmentModifyEvent;
import me.demro.dlibs.dbans.api.event.PunishmentRevokeEvent;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AddonRuntime {

    private final TelegramConfig config;
    private final TelegramClient client;
    private final NotificationService notificationService;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public @NotNull SupportedLocale locale() {
        return config.getLocale();
    }

    public void handle(@NotNull PunishmentCreateEvent event) {
        if (config.isNotifyOnCreate() && isIncluded(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    public void handle(@NotNull PunishmentRevokeEvent event) {
        if (config.isNotifyOnRevoke() && isIncluded(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    public void handle(@NotNull PunishmentModifyEvent event) {
        if (config.isNotifyOnModify() && isIncluded(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    public void handle(@NotNull PunishmentExpireEvent event) {
        if (config.isNotifyOnExpire() && isIncluded(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    private boolean isIncluded(@NotNull PunishmentType type) {
        return !config.getIgnoredTypes().contains(type);
    }

    @NotNull CompletableFuture<Void> sendTestMessage(@NotNull String text) {
        return client.sendMessage(text);
    }

    void shutdown() {
        if (stopped.compareAndSet(false, true)) {
            client.shutdown();
        }
    }

}