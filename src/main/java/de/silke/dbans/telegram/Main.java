package de.silke.dbans.telegram;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.command.TelegramCommand;
import de.silke.dbans.telegram.config.TelegramConfig;
import de.silke.dbans.telegram.listener.PunishmentEventListener;
import de.silke.dbans.telegram.locale.MessageProvider;
import de.silke.dbans.telegram.locale.SupportedLocale;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main extends JavaPlugin {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private TelegramClient client;
    private PunishmentEventListener listener;

    private static void notifyAboutException(
            @NotNull CommandSender sender,
            @NotNull Throwable exception
    ) {
        sender.sendMessage("Test message could not be delivered. Check the console for details");
        log.log(Level.SEVERE, "Failed to deliver Telegram test message", exception);
    }

    private static void notifyAboutSuccess(@NotNull CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            log.info("Test message delivered");
        } else {
            sender.sendMessage("Test message delivered");
        }
    }

    private static @NotNull Throwable unwrap(@NotNull Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (SupportedLocale locale : SupportedLocale.values()) {
            String path = "locale/" + locale.getCode() + ".yml";
            if (!new File(getDataFolder(), path).exists()) {
                saveResource(path, false);
            }
        }

        TelegramConfig telegramConfig = new TelegramConfig(getConfig());
        if (!telegramConfig.isValid()) {
            log.severe("Telegram token or chat ID is not configured. Disabling plugin");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        client = new TelegramClient(telegramConfig);
        MessageProvider messageProvider = new MessageProvider(this, telegramConfig.getLocale());
        NotificationService notificationService = new NotificationService(client,
                                                                          messageProvider,
                                                                          telegramConfig.getTimezone());
        listener = new PunishmentEventListener(telegramConfig, notificationService);

        getServer().getPluginManager().registerEvents(listener, this);
        Objects.requireNonNull(getCommand("dbanstelegram")).setExecutor(new TelegramCommand(this));
        log.info("Enabled (locale: " + telegramConfig.getLocale().getCode() + ")");
    }

    @Override
    public void onDisable() {
        if (client != null) client.shutdown();
    }

    public void reload(@NotNull CommandSender sender) {
        reloadConfig();

        TelegramConfig telegramConfig = new TelegramConfig(getConfig());
        if (!telegramConfig.isValid()) {
            sender.sendMessage("Telegram token or chat ID is not configured. " +
                               "Reload aborted, keeping previous settings");
            return;
        }

        TelegramClient oldClient = client;
        client = new TelegramClient(telegramConfig);

        MessageProvider messageProvider = new MessageProvider(this, telegramConfig.getLocale());
        NotificationService notificationService = new NotificationService(client,
                                                                          messageProvider,
                                                                          telegramConfig.getTimezone()
        );
        listener.update(telegramConfig, notificationService);
        oldClient.shutdown();

        sender.sendMessage("dbans-telegram-addon reloaded " +
                           "(locale: " + telegramConfig.getLocale().getCode() + ")");
        log.info("Reloaded (locale: " + telegramConfig.getLocale().getCode() + ")");
    }

    public void test(@NotNull CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            log.info("Sending test message...");
        } else {
            sender.sendMessage("Sending test message...");
        }

        client.sendMessage("[TEST] By " + sender.getName())
              .whenComplete((ignored, throwable) ->
                                    getServer().getScheduler().runTask(this, () -> {
                                        if (throwable == null) {
                                            notifyAboutSuccess(sender);
                                        } else {
                                            notifyAboutException(sender, unwrap(throwable));
                                        }
                                    })
              );
    }
}
