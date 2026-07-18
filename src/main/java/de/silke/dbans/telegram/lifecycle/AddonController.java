package de.silke.dbans.telegram.lifecycle;

import lombok.RequiredArgsConstructor;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.event.PunishmentExpireEvent;
import me.demro.dlibs.dbans.api.event.PunishmentModifyEvent;
import me.demro.dlibs.dbans.api.event.PunishmentRevokeEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class AddonController {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final Object lock = new Object();
    private final Plugin plugin;
    private final AddonRuntimeFactory runtimeFactory;

    private volatile Snapshot snapshot = Snapshot.inactive();
    private boolean started;

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
        return snapshot.state();
    }

    public void start(@NotNull FileConfiguration config) {
        synchronized (lock) {
            if (started) {
                log.warning("start() was already called once; ignoring the repeated call");
                return;
            }
            started = true;

            AddonRuntimeFactory.Result result = createSafely(config);
            if (result.isValid()) {
                AddonRuntime runtime = result.runtime();
                snapshot = Snapshot.active(runtime);
                logWarnings(result.warnings());
                log.info("Enabled (locale: " + runtime.locale().getCode() + ")");
            } else {
                snapshot = Snapshot.inactive();
                log.warning("Telegram integration is inactive (" + String.join("; ", result.errors()) + "). "
                            + "Fill in the configuration and use /dbanstelegram reload to activate it");
            }
        }
    }

    public void reload(@NotNull CommandSender sender, @NotNull FileConfiguration config) {
        AddonRuntime oldRuntime;
        AddonRuntime newRuntime;
        synchronized (lock) {
            Snapshot current = snapshot;
            if (current.state() == AddonState.STOPPED) {
                sender.sendMessage("dbans-telegram-addon is stopped and cannot be reloaded");
                return;
            }

            AddonRuntimeFactory.Result result = createSafely(config);
            if (!result.isValid()) {
                sender.sendMessage("Reload aborted, keeping previous settings: " + String.join("; ", result.errors()));
                return;
            }

            oldRuntime = current.runtime();
            newRuntime = result.runtime();
            snapshot = Snapshot.active(newRuntime);
            logWarnings(result.warnings());
        }

        if (oldRuntime != null) {
            oldRuntime.shutdown();
        }

        sender.sendMessage("dbans-telegram-addon reloaded (locale: " + newRuntime.locale().getCode() + ")");
        log.info("Reloaded (locale: " + newRuntime.locale().getCode() + ")");
    }

    public void stop() {
        AddonRuntime current;
        synchronized (lock) {
            if (snapshot.state() == AddonState.STOPPED) {
                return;
            }

            current = snapshot.runtime();
            snapshot = Snapshot.stopped();
        }
        if (current != null) {
            current.shutdown();
        }
    }

    public void test(@NotNull CommandSender sender) {
        Snapshot current = snapshot;
        if (current.state() == AddonState.STOPPED) {
            sender.sendMessage("dbans-telegram-addon is stopped");
            return;
        }

        AddonRuntime runtime = current.runtime();
        if (current.state() != AddonState.ACTIVE || runtime == null) {
            sender.sendMessage("Telegram integration is not configured. " +
                               "Fill in the configuration and use /dbanstelegram reload to activate it");
            return;
        }

        if (sender instanceof ConsoleCommandSender) {
            log.info("Sending test message...");
        } else {
            sender.sendMessage("Sending test message...");
        }

        runtime.sendTestMessage("[TEST] By " + sender.getName())
               .whenComplete((ignored, throwable) -> {
                   if (snapshot.state() == AddonState.STOPPED) {
                       return;
                   }
                   try {
                       plugin.getServer().getScheduler().runTask(plugin, () -> {
                           if (throwable == null) {
                               notifyAboutSuccess(sender);
                           } else {
                               notifyAboutException(sender, unwrap(throwable));
                           }
                       });
                   } catch (IllegalPluginAccessException e) {
                       log.warning("Could not deliver test message result; the plugin is disabling");
                   }
               });
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

    private @NotNull AddonRuntimeFactory.Result createSafely(@NotNull FileConfiguration config) {
        try {
            return runtimeFactory.create(config);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unexpected failure while creating the Telegram runtime", e);
            return AddonRuntimeFactory.Result.invalid(List.of("Unexpected error: " + e.getMessage()));
        }
    }

    private void logWarnings(@NotNull List<String> warnings) {
        if (!warnings.isEmpty()) {
            log.warning("Telegram configuration warnings: " + String.join("; ", warnings));
        }
    }

    private @Nullable AddonRuntime activeRuntime() {
        Snapshot current = snapshot;
        return current.state() == AddonState.ACTIVE ? current.runtime() : null;
    }

    private record Snapshot(AddonState state, AddonRuntime runtime) {

        @Contract(" -> new")
        static @NotNull Snapshot inactive() {
            return new Snapshot(AddonState.INACTIVE, null);
        }

        @Contract("_ -> new")
        static @NotNull Snapshot active(@NotNull AddonRuntime runtime) {
            return new Snapshot(AddonState.ACTIVE, runtime);
        }

        @Contract(" -> new")
        static @NotNull Snapshot stopped() {
            return new Snapshot(AddonState.STOPPED, null);
        }

    }

}