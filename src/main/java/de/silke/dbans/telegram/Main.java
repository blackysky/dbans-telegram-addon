package de.silke.dbans.telegram;

import de.silke.dbans.telegram.command.TelegramCommand;
import de.silke.dbans.telegram.lifecycle.AddonController;
import de.silke.dbans.telegram.lifecycle.AddonRuntimeFactory;
import de.silke.dbans.telegram.listener.PunishmentEventListener;
import de.silke.dbans.telegram.locale.SupportedLocale;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class Main extends JavaPlugin {

    private AddonController controller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (SupportedLocale locale : SupportedLocale.values()) {
            String path = "locale/" + locale.getCode() + ".yml";
            if (!new File(getDataFolder(), path).exists()) {
                saveResource(path, false);
            }
        }

        controller = new AddonController(this, new AddonRuntimeFactory(this));
        getServer().getPluginManager().registerEvents(new PunishmentEventListener(controller), this);
        Objects.requireNonNull(getCommand("dbanstelegram")).setExecutor(
                new TelegramCommand(this, controller)
        );
        controller.start(getConfig());
    }

    @Override
    public void onDisable() {
        if (controller != null) {
            controller.stop();
        }
    }

}