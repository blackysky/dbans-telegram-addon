package de.silke.dbans.telegram.lifecycle;

import lombok.RequiredArgsConstructor;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.event.PunishmentExpireEvent;
import me.demro.dlibs.dbans.api.event.PunishmentModifyEvent;
import me.demro.dlibs.dbans.api.event.PunishmentRevokeEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class AddonController {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final Plugin plugin;
    private final AddonRuntimeFactory runtimeFactory;

    private volatile AddonRuntime runtime;
    private volatile AddonState state = AddonState.INACTIVE;

    private static void notifyAboutSuccess(@NotNull CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            log.info("Test message delivered");
        } else {
            sender.sendMessage("Test message delivered");
        }
    }

    private static void notifyAboutException(@NotNull CommandSender sender, @NotNull Throwable exception) {
        sender.sendMessage("Test message could not be delivered. Check the console for details");
        log.log(Level.SEVERE, "Failed to deliver Telegram test message", exception);
    }

    private static @NotNull Throwable unwrap(@NotNull Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @NotNull AddonState getState() {
        return state;
    }

    public void start(@NotNull FileConfiguration config) {
        AddonRuntimeFactory.Result result = runtimeFactory.create(config);
        if (result.isValid()) {
            runtime = result.runtime();
            state = AddonState.ACTIVE;
            log.info("Enabled (locale: " + runtime.locale().getCode() + ")");
        } else {
            state = AddonState.INACTIVE;
            log.warning("Telegram integration is inactive (" + String.join("; ", result.errors()) + "). "
                        + "Fill in the configuration and run /dbanstelegram reload to activate it");
        }
    }

    @SuppressWarnings("MethodWithMultipleReturnPoints")
    public void reload(@NotNull CommandSender sender, @NotNull FileConfiguration config) {
        if (state == AddonState.STOPPED) {
            sender.sendMessage("dbans-telegram-addon is stopped and cannot be reloaded");
            return;
        }

        AddonRuntimeFactory.Result result = runtimeFactory.create(config);
        if (!result.isValid()) {
            sender.sendMessage("Reload aborted, keeping previous settings: " + String.join("; ", result.errors()));
            return;
        }

        AddonRuntime oldRuntime = runtime;
        runtime = result.runtime();
        state = AddonState.ACTIVE;
        if (oldRuntime != null) {
            oldRuntime.shutdown();
        }

        sender.sendMessage("dbans-telegram-addon reloaded (locale: " + runtime.locale().getCode() + ")");
        log.info("Reloaded (locale: " + runtime.locale().getCode() + ")");
    }

    public void stop() {
        if (state == AddonState.STOPPED) {
            return;
        }

        state = AddonState.STOPPED;
        AddonRuntime current = runtime;
        if (current != null) {
            current.shutdown();
        }
    }

    @SuppressWarnings("MethodWithMultipleReturnPoints")
    public void test(@NotNull CommandSender sender) {
        if (state == AddonState.STOPPED) {
            sender.sendMessage("dbans-telegram-addon is stopped");
            return;
        }

        AddonRuntime current = runtime;
        if (state != AddonState.ACTIVE || current == null) {
            sender.sendMessage("Telegram integration is not configured. " +
                               "Fill in the configuration and run /dbanstelegram reload to activate it");
            return;
        }

        if (sender instanceof ConsoleCommandSender) {
            log.info("Sending test message...");
        } else {
            sender.sendMessage("Sending test message...");
        }

        current.sendTestMessage("[TEST] By " + sender.getName())
               .whenComplete((ignored, throwable) ->
                                     plugin.getServer().getScheduler().runTask(plugin, () -> {
                                         if (throwable == null) {
                                             notifyAboutSuccess(sender);
                                         } else {
                                             notifyAboutException(sender, unwrap(throwable));
                                         }
                                     })
               );
    }

    public void onCreate(@NotNull PunishmentCreateEvent event) {
        AddonRuntime current = activeRuntime();
        if (current != null) {
            current.handle(event);
        }
    }

    public void onRevoke(@NotNull PunishmentRevokeEvent event) {
        AddonRuntime current = activeRuntime();
        if (current != null) {
            current.handle(event);
        }
    }

    public void onModify(@NotNull PunishmentModifyEvent event) {
        AddonRuntime current = activeRuntime();
        if (current != null) {
            current.handle(event);
        }
    }

    public void onExpire(@NotNull PunishmentExpireEvent event) {
        AddonRuntime current = activeRuntime();
        if (current != null) {
            current.handle(event);
        }
    }

    private @Nullable AddonRuntime activeRuntime() {
        return state == AddonState.ACTIVE ? runtime : null;
    }

}