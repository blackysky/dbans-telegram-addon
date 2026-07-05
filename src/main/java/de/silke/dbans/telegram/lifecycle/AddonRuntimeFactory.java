package de.silke.dbans.telegram.lifecycle;

import de.silke.dbans.telegram.application.NotificationService;
import de.silke.dbans.telegram.client.TelegramClient;
import de.silke.dbans.telegram.config.TelegramConfig;
import de.silke.dbans.telegram.locale.MessageProvider;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@RequiredArgsConstructor
public class AddonRuntimeFactory {

    private final Plugin plugin;

    public @NotNull Result create(@NotNull FileConfiguration config) {
        TelegramConfig telegramConfig = new TelegramConfig(config);
        List<String> errors = telegramConfig.validationErrors();
        if (!errors.isEmpty()) {
            return Result.invalid(errors);
        }

        TelegramClient client = new TelegramClient(telegramConfig);
        MessageProvider messageProvider = new MessageProvider(plugin, telegramConfig.getLocale());
        NotificationService notificationService = new NotificationService(
                client, messageProvider, telegramConfig.getTimezone()
        );
        return Result.success(new AddonRuntime(telegramConfig, client, notificationService));
    }

    public record Result(AddonRuntime runtime, List<String> errors) {

        @Contract("_ -> new")
        static @NotNull Result success(@NotNull AddonRuntime runtime) {
            return new Result(runtime, List.of());
        }

        @Contract("_ -> new")
        static @NotNull Result invalid(@NotNull List<String> errors) {
            return new Result(null, errors);
        }

        @Contract(pure = true)
        boolean isValid() {
            return runtime != null;
        }
    }

}