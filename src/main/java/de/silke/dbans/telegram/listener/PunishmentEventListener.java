package de.silke.dbans.telegram.listener;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.config.TelegramConfig;
import lombok.RequiredArgsConstructor;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.event.PunishmentExpireEvent;
import me.demro.dlibs.dbans.api.event.PunishmentModifyEvent;
import me.demro.dlibs.dbans.api.event.PunishmentRevokeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class PunishmentEventListener implements Listener {

    private final TelegramConfig config;
    private final NotificationService notificationService;

    @EventHandler
    public void onCreate(@NotNull PunishmentCreateEvent event) {
        if (config.isNotifyOnCreate()) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onRevoke(@NotNull PunishmentRevokeEvent event) {
        if (config.isNotifyOnRevoke()) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onModify(@NotNull PunishmentModifyEvent event) {
        if (config.isNotifyOnModify()) {
            notificationService.notify(event);
        }
    }

    @EventHandler
    public void onExpire(@NotNull PunishmentExpireEvent event) {
        if (config.isNotifyOnExpire()) {
            notificationService.notify(event);
        }
    }
}
