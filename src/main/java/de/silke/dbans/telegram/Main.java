package de.silke.dbans.telegram;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.config.TelegramConfig;
import de.silke.dbans.telegram.listener.PunishmentEventListener;
import de.silke.dbans.telegram.locale.MessageProvider;
import de.silke.dbans.telegram.locale.SupportedLocale;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class Main extends JavaPlugin {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private TelegramClient client;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (SupportedLocale locale : SupportedLocale.values()) {
            saveResource("locale/" + locale.getCode() + ".yml", false);
        }

        TelegramConfig telegramConfig = new TelegramConfig(getConfig());
        if (!telegramConfig.isValid()) {
            log.severe("Telegram token or chat ID is not configured. Disabling plugin");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        client = new TelegramClient(telegramConfig);
        MessageProvider messageProvider = new MessageProvider(this, telegramConfig.getLocale());
        NotificationService notificationService = new NotificationService(client, messageProvider, telegramConfig.getTimezone());
        PunishmentEventListener listener = new PunishmentEventListener(telegramConfig, notificationService);

        getServer().getPluginManager().registerEvents(listener, this);
        log.info("Enabled (locale: " + telegramConfig.getLocale().getCode() + ")");
    }

    @Override
    public void onDisable() {
        if (client != null) client.shutdown();
    }
}
