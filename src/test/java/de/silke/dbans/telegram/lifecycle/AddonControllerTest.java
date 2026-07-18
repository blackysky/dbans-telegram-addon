package de.silke.dbans.telegram.lifecycle;

import me.demro.dlibs.dbans.api.event.EventOrigin;
import me.demro.dlibs.dbans.api.event.PunishmentCreateEvent;
import me.demro.dlibs.dbans.api.punishment.Punishment;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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

    private BukkitScheduler stubImmediateScheduler() {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        return scheduler;
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

        verify(factory, times(1)).create(any(FileConfiguration.class));

        AddonRuntime newRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(newRuntime));
        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.STOPPED);
        verify(factory, times(1)).create(any(FileConfiguration.class));
        verifyNoInteractions(newRuntime);
    }

    @Test
    void start_calledTwice_secondCallIsIgnored() {
        AddonRuntime first = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(first));
        controller.start(mock(FileConfiguration.class));

        AddonRuntime second = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(second));
        controller.start(mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
        verify(factory, times(1)).create(any(FileConfiguration.class));
        verifyNoInteractions(second);
        verify(first, never()).shutdown();
    }

    @Test
    void start_calledAgainAfterInactive_isIgnored() {
        stubCreate(AddonRuntimeFactory.Result.invalid(List.of("bad")));
        controller.start(mock(FileConfiguration.class));
        assertThat(controller.getState()).isEqualTo(AddonState.INACTIVE);

        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.INACTIVE);
        verifyNoInteractions(runtime);
    }

    @Test
    void start_calledAfterStop_isIgnored() {
        AddonRuntime runtime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        controller.stop();

        AddonRuntime another = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(another));
        controller.start(mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.STOPPED);
        verifyNoInteractions(another);
    }

    @Test
    void reload_whenFactoryThrows_preservesOldRuntimeAndReportsError() {
        AddonRuntime oldRuntime = validRuntime();
        stubCreate(AddonRuntimeFactory.Result.success(oldRuntime));
        controller.start(mock(FileConfiguration.class));

        when(factory.create(any(FileConfiguration.class))).thenThrow(new RuntimeException("boom"));

        controller.reload(sender, mock(FileConfiguration.class));

        assertThat(controller.getState()).isEqualTo(AddonState.ACTIVE);
        verify(oldRuntime, never()).shutdown();
        verify(sender).sendMessage(contains("Reload aborted, keeping previous settings"));

        PunishmentCreateEvent event = createEvent();
        controller.onCreate(event);
        verify(oldRuntime).handle(event);
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

    @Test
    void test_whileActive_onSuccess_notifiesSenderOnMainThread() {
        stubImmediateScheduler();
        AddonRuntime runtime = validRuntime();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        when(runtime.sendTestMessage(any())).thenReturn(sendResult);
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        when(sender.getName()).thenReturn("Admin");

        controller.test(sender);
        sendResult.complete(null);

        verify(sender).sendMessage("Test message delivered");
    }

    @Test
    void test_whileActive_onFailure_notifiesSenderOfError() {
        stubImmediateScheduler();
        AddonRuntime runtime = validRuntime();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        when(runtime.sendTestMessage(any())).thenReturn(sendResult);
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        when(sender.getName()).thenReturn("Admin");

        controller.test(sender);
        sendResult.completeExceptionally(new RuntimeException("network down"));

        verify(sender).sendMessage(contains("could not be delivered"));
    }

    @Test
    void test_completionAfterStop_doesNotScheduleBukkitTask() {
        AddonRuntime runtime = validRuntime();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        when(runtime.sendTestMessage(any())).thenReturn(sendResult);
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        when(sender.getName()).thenReturn("Admin");

        controller.test(sender);
        controller.stop();
        sendResult.complete(null);

        verifyNoInteractions(plugin);
    }

    @Test
    void test_completionRacesPluginDisable_doesNotPropagate() {
        AddonRuntime runtime = validRuntime();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        when(runtime.sendTestMessage(any())).thenReturn(sendResult);
        stubCreate(AddonRuntimeFactory.Result.success(runtime));
        controller.start(mock(FileConfiguration.class));
        when(sender.getName()).thenReturn("Admin");

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doThrow(new IllegalPluginAccessException("plugin disabled"))
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));

        controller.test(sender);

        sendResult.complete(null);
    }

}