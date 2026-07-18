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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class AddonRuntimeFactory {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final Plugin plugin;

    public @NotNull Result create(@NotNull FileConfiguration config) {
        TelegramConfig telegramConfig;
        try {
            telegramConfig = new TelegramConfig(config);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to read Telegram configuration", e);
            return Result.invalid(List.of("Failed to read configuration: " + e.getMessage()));
        }

        List<String> errors = telegramConfig.validationErrors();
        if (!errors.isEmpty()) {
            return Result.invalid(errors);
        }

        MessageProvider messageProvider;
        try {
            messageProvider = new MessageProvider(plugin, telegramConfig.getLocale());
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to load locale messages", e);
            return Result.invalid(List.of("Failed to load locale messages: " + e.getMessage()));
        }

        TelegramClient client;
        try {
            client = new TelegramClient(telegramConfig);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to create the Telegram client", e);
            return Result.invalid(List.of("Failed to create the Telegram client: " + e.getMessage()));
        }

        try {
            NotificationService notificationService = new NotificationService(
                    client, messageProvider, telegramConfig.getTimezone()
            );
            return Result.success(
                    new AddonRuntime(telegramConfig, client, notificationService),
                    telegramConfig.diagnostics().warnings()
            );
        } catch (Exception e) {
            client.shutdown();
            log.log(Level.SEVERE, "Failed to build the Telegram runtime", e);
            return Result.invalid(List.of("Failed to build the Telegram runtime: " + e.getMessage()));
        }
    }

    public record Result(@Nullable AddonRuntime runtime,
                         @NotNull List<String> errors,
                         @NotNull List<String> warnings
    ) {

        public Result {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
            if ((runtime == null) == errors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Result must have either a runtime or at least one error, not both or neither");
            }
        }

        @Contract("_, _ -> new")
        static @NotNull Result success(@NotNull AddonRuntime runtime, @NotNull List<String> warnings) {
            return new Result(runtime, List.of(), warnings);
        }

        @Contract("_ -> new")
        static @NotNull Result success(@NotNull AddonRuntime runtime) {
            return success(runtime, List.of());
        }

        @Contract("_ -> new")
        static @NotNull Result invalid(@NotNull List<String> errors) {
            return new Result(null, errors, List.of());
        }

        @Contract(pure = true)
        boolean isValid() {
            return runtime != null;
        }
    }

}