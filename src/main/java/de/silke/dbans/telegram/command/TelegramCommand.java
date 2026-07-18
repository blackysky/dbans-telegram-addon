package de.silke.dbans.telegram.command;

import de.silke.dbans.telegram.lifecycle.AddonController;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

@RequiredArgsConstructor
public class TelegramCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final AddonController controller;

    @SuppressWarnings("MethodWithMultipleReturnPoints")
    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        if (args.length != 1) {
            return false;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadConfig();
                controller.reload(sender, plugin.getConfig());
            }
            case "test" -> controller.test(sender);
            default -> {
                return false;
            }
        }
        return true;
    }
}
