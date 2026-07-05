package de.silke.dbans.telegram.listener;

import de.silke.dbans.telegram.lifecycle.AddonController;
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

    private final AddonController controller;

    @EventHandler
    public void onCreate(@NotNull PunishmentCreateEvent event) {
        controller.onCreate(event);
    }

    @EventHandler
    public void onRevoke(@NotNull PunishmentRevokeEvent event) {
        controller.onRevoke(event);
    }

    @EventHandler
    public void onModify(@NotNull PunishmentModifyEvent event) {
        controller.onModify(event);
    }

    @EventHandler
    public void onExpire(@NotNull PunishmentExpireEvent event) {
        controller.onExpire(event);
    }

}