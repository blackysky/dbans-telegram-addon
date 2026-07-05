package de.silke.dbans.telegram.command;

import de.silke.dbans.telegram.Main;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class TelegramCommand implements CommandExecutor {

    private final Main plugin;

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
        switch (args[0].toLowerCase()) {
            case "reload" -> plugin.reload(sender);
            case "test" -> plugin.test(sender);
            default -> {
                return false;
            }
        }
        return true;
    }
}
