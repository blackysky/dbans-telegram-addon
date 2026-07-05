package de.silke.dbans.telegram.lifecycle;

import me.demro.dlibs.dbans.api.event.EventOrigin;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.punishment.Punishment;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class AddonControllerTest {

    private Plugin plugin;
    private AddonRuntimeFactory factory;
    private CommandSender sender;
    private AddonController controller;

    private static PunishmentCreateEvent createEvent() {
        return new PunishmentCreateEvent(mock(Punishment.class), EventOrigin.COMMAND, Instant.now(), false);
    }

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        factory = mock(AddonRuntimeFactory.class);
        sender = mock(CommandSender.class);
        controller = new AddonController(plugin, factory);
    }

    private AddonRuntime validRuntime() {
        AddonRuntime runtime = mock(AddonRuntime.class);
        when(runtime.locale()).thenReturn(de.silke.dbans.telegram.locale.SupportedLocale.EN);
        return runtime;
    }

    private void stubCreate(AddonRuntimeFactory.Result result) {
        when(factory.create(any(FileConfiguration.class))).thenReturn(result);
    }

    @Test
    void start_validConfig_becomesActive() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));

        controller.start(mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
    }

    @Test
    void start_invalidConfig_becomesInactive() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("client.token is not configured")));

        controller.start(mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.INACTIVE);
    }

    @Test
    void reload_fromInactiveWithValidConfig_becomesActive() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.start(mock(FileConfiguration.class));
        assertThat(controller.getState()).isEqualTo(AddonState.INACTIVE);

        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
    }

    @Test
    void reload_fromInactiveWithInvalidConfig_staysInactive() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.start(mock(FileConfiguration.class));

        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.INACTIVE);
        verify(sender).sendMessage(contains("Reload aborted, keeping previous settings"));
    }

    @Test
    void reload_fromActiveWithValidConfig_replacesRuntimeAndClosesOldExactlyOnce() {
        AddonRuntime oldRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(oldRuntime));
        controller.start(mock(FileConfiguration.class));

        AddonRuntime newRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(newRuntime));
        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
        verify(oldRuntime, times(1)).shutdown();
        verify(newRuntime, never()).shutdown();
    }

    @Test
    void reload_fromActiveWithInvalidConfig_preservesOldRuntime() {
        AddonRuntime oldRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(oldRuntime));
        controller.start(mock(FileConfiguration.class));

        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
        verify(oldRuntime, never()).shutdown();

        PunishmentCreateEvent event = createEvent();
        controller.onCreate(event);
        verify(oldRuntime).handle(event);
    }

    @Test
    void reload_newEventsUseNewRuntimeNotOldOne() {
        AddonRuntime oldRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(oldRuntime));
        controller.start(mock(FileConfiguration.class));

        PunishmentCreateEvent beforeReload = createEvent();
        controller.onCreate(beforeReload);
        verify(oldRuntime, times(1)).handle(beforeReload);

        AddonRuntime newRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(newRuntime));
        controller.reload(sender, mock(FileConfiguration.class));

        PunishmentCreateEvent afterReload = createEvent();
        controller.onCreate(afterReload);

        verify(newRuntime, times(1)).handle(afterReload);
        verify(oldRuntime, never()).handle(afterReload);
        verify(newRuntime, never()).handle(beforeReload);
    }

    @Test
    void stop_closesActiveRuntime() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));

        controller.stop();

        assertThat(controller.getState()).isEqualTo(AddonState.STOPPED);
        verify(runtime, times(1)).shutdown();
    }

    @Test
    void stop_calledTwice_shutsDownRuntimeOnce() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));

        controller.stop();
        controller.stop();

        verify(runtime, times(1)).shutdown();
    }

    @Test
    void onCreate_afterStop_doesNotForwardEvent() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        controller.stop();

        controller.onCreate(createEvent());

        verify(runtime, never()).handle(any(PunishmentCreateEvent.class));
    }

    @Test
    void reload_afterStop_isRejected() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        controller.stop();

        AddonRuntime newRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(newRuntime));
        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.STOPPED);
        verifyNoInteractions(newRuntime);
    }

    @Test
    void onCreate_whileInactive_doesNotThrow() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.start(mock(FileConfiguration.class));

        controller.onCreate(createEvent());
    }

    @Test
    void test_whileInactive_doesNotAccessRuntime() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.start(mock(FileConfiguration.class));

        controller.test(sender);

        verify(sender).sendMessage(contains("not configured"));
        verifyNoInteractions(plugin);
    }

    @Test
    void test_whileActive_sendsThroughCurrentRuntime() {
        AddonRuntime runtime = validRuntime();
        when(runtime.sendTestMessage(any())).thenReturn(new CompletableFuture<>());
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        when(sender.getName()).thenReturn("Admin");

        controller.test(sender);

        verify(runtime).sendTestMessage("[TEST] By Admin");
    }

}