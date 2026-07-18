package de.silke.dbans.telegram.command;

import de.silke.dbans.telegram.lifecycle.AddonController;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TelegramCommandTest {

    private JavaPlugin plugin;
    private AddonController controller;
    private CommandSender sender;
    private Command command;
    private TelegramCommand telegramCommand;
    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        controller = mock(AddonController.class);
        sender = mock(CommandSender.class);
        command = mock(Command.class);
        telegramCommand = new TelegramCommand(plugin, controller);
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void onCommand_reload_dispatchesRegardlessOfCase() {
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);

        boolean handled = telegramCommand.onCommand(sender, command, "dbanstelegram", new String[]{"ReLoAd"});

        assertThat(handled).isTrue();
        verify(plugin).reloadConfig();
        verify(controller).reload(sender, config);
    }

    @Test
    void onCommand_test_dispatchesRegardlessOfCase() {
        boolean handled = telegramCommand.onCommand(sender, command, "dbanstelegram", new String[]{"TEST"});

        assertThat(handled).isTrue();
        verify(controller).test(sender);
    }

    @Test
    void onCommand_underTurkishDefaultLocale_stillDispatchesCorrectly() {
        Locale.setDefault(new Locale("tr", "TR"));
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);

        boolean handled = telegramCommand.onCommand(sender, command, "dbanstelegram", new String[]{"ReLoAd"});

        assertThat(handled).isTrue();
        verify(controller).reload(eq(sender), any());
    }

    @Test
    void onCommand_unknownSubcommand_returnsFalse() {
        boolean handled = telegramCommand.onCommand(sender, command, "dbanstelegram", new String[]{"bogus"});

        assertThat(handled).isFalse();
        verifyNoInteractions(controller);
    }

    @Test
    void onCommand_wrongArgCount_returnsFalse() {
        boolean handled = telegramCommand.onCommand(sender, command, "dbanstelegram", new String[]{});

        assertThat(handled).isFalse();
        verifyNoInteractions(controller);
    }

}