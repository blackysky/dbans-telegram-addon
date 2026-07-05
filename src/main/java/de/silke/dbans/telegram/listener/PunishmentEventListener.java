package de.silke.dbans.telegram.listener;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.config.TelegramConfig;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.event.PunishmentExpireEvent;
import me.demro.dlibs.dbans.api.event.PunishmentModifyEvent;
import me.demro.dlibs.dbans.api.event.PunishmentRevokeEvent;
import me.demro.dlibs.dbans.api.punishment.PunishmentType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class PunishmentEventListener implements Listener {

    private TelegramConfig config;
    private NotificationService notificationService;

    @Contract(pure = true)
    public PunishmentEventListener(@NotNull TelegramConfig config,
                                   @NotNull NotificationService notificationService
    ) {
        this.config = config;
        this.notificationService = notificationService;
    }

    public void update(@NotNull TelegramConfig config,
                       @NotNull NotificationService notificationService
    ) {
        this.config = config;
        this.notificationService = notificationService;
    }

    @EventHandler
    public void onCreate(@NotNull PunishmentCreateEvent event) {
        if (config.isNotifyOnCreate() && !isIgnored(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onRevoke(@NotNull PunishmentRevokeEvent event) {
        if (config.isNotifyOnRevoke() && !isIgnored(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onModify(@NotNull PunishmentModifyEvent event) {
        if (config.isNotifyOnModify() && !isIgnored(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onExpire(@NotNull PunishmentExpireEvent event) {
        if (config.isNotifyOnExpire() && !isIgnored(event.punishment().type())) {
            notificationService.notify(event);
        }
    }

    private boolean isIgnored(@NotNull PunishmentType type) {
        return config.getIgnoredTypes().contains(type);
    }
}
