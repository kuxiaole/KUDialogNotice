package dev.kuxiaole.kudialognotice.flow;

import dev.kuxiaole.kudialognotice.KUDialogNoticePlugin;
import dev.kuxiaole.kudialognotice.compat.FakePlayerPlusSupportTest;
import dev.kuxiaole.kudialognotice.config.*;
import dev.kuxiaole.kudialognotice.listener.FallbackGuardListener;
import dev.kuxiaole.kudialognotice.scheduler.SchedulerFacade;
import dev.kuxiaole.kudialognotice.storage.DistributedStateStore;
import dev.kuxiaole.kudialognotice.ui.NoticePresenter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FakePlayerNoticeFlowTest {
    private final KUDialogNoticePlugin plugin = mock(KUDialogNoticePlugin.class);
    private final DistributedStateStore store = mock(DistributedStateStore.class);
    private final SchedulerFacade scheduler = mock(SchedulerFacade.class);
    private final NoticePresenter presenter = mock(NoticePresenter.class);
    private final FakePlayerPlusSupportTest.Registry registry = new FakePlayerPlusSupportTest.Registry();
    private final Plugin owner = FakePlayerPlusSupportTest.owner(registry);
    private NoticeFlowService flows;

    @BeforeEach void setup() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin("FakePlayerPlus")).thenReturn(owner);
        when(plugin.configuration()).thenReturn(configuration());
        when(plugin.stateStore()).thenReturn(store);
        when(plugin.scheduler()).thenReturn(scheduler);
        when(store.load(any(), anyString(), anyLong())).thenReturn(new CompletableFuture<>());
        flows = new NoticeFlowService(plugin, presenter);
    }

    @Test void botNeverCreatesConsentOrPreviewSession() {
        Player bot = player();
        registry.players.put(bot.getUniqueId(), new FakePlayerPlusSupportTest.Bot(bot));
        flows.onAuthenticated(bot);
        flows.onAuthenticated(bot);
        assertFalse(flows.startPreview(bot, "rules"));
        assertFalse(flows.startPreview(bot, "changelog"));
        assertFalse(flows.isBlocking(bot.getUniqueId()));
        verifyNoInteractions(store, scheduler, presenter);
    }

    @Test void realPlayerStillGetsSessionAndSwapGuard() throws Exception {
        Player real = player();
        flows.onAuthenticated(real);
        forceRules(real);
        assertTrue(flows.isBlocking(real.getUniqueId()));
        PlayerSwapHandItemsEvent event = swap(real);
        new FallbackGuardListener(flows).onSwapHands(event);
        verify(event).setCancelled(true);
        verify(store).load(any(), eq("test"), eq(1L));
        verify(scheduler).runAtEntityDelayed(eq(real), any(), any(), eq(1L));
    }

    @Test void clearsStaleBotSessionWithoutClearingRealPlayersOrOtherCancellation() throws Exception {
        Player bot = player();
        Player real = player();
        flows.onAuthenticated(bot);
        flows.onAuthenticated(real);
        forceRules(bot);
        forceRules(real);
        registry.players.put(bot.getUniqueId(), new FakePlayerPlusSupportTest.Bot(bot));
        clearInvocations(store, scheduler, presenter);
        flows.onAuthenticated(bot);
        assertFalse(flows.isBlocking(bot.getUniqueId()));
        assertTrue(flows.isBlocking(real.getUniqueId()));
        PlayerSwapHandItemsEvent event = swap(bot);
        when(event.isCancelled()).thenReturn(true);
        new FallbackGuardListener(flows).onSwapHands(event);
        verify(event, never()).setCancelled(anyBoolean());
        verifyNoInteractions(store, scheduler, presenter);
    }

    @Test void genuinePlayerWithRegisteredUuidStillRequiresConsent() throws Exception {
        Player real = player();
        Player other = FakePlayerPlusSupportTest.player(real.getUniqueId());
        registry.players.put(real.getUniqueId(), new FakePlayerPlusSupportTest.Bot(other));
        flows.onAuthenticated(real);
        forceRules(real);
        assertTrue(flows.isBlocking(real.getUniqueId()));
        verify(store).load(any(), eq("test"), eq(1L));
    }

    @Test void disabledIntegrationDoesNotSkipConsent() throws Exception {
        Player player = player();
        registry.players.put(player.getUniqueId(), new FakePlayerPlusSupportTest.Bot(player));
        when(owner.isEnabled()).thenReturn(false);
        flows.onAuthenticated(player);
        forceRules(player);
        assertTrue(flows.isBlocking(player.getUniqueId()));
    }

    @Test void shutdownDoesNotCreateSessionsOrQueryOtherPlugin() {
        flows.shutdown();
        clearInvocations(owner);
        flows.onAuthenticated(player());
        assertFalse(flows.startPreview(player(), "rules"));
        verifyNoInteractions(store, scheduler, presenter, owner);
    }

    private Player player() {
        Player player = FakePlayerPlusSupportTest.player(UUID.randomUUID());
        when(player.getName()).thenReturn("Tester");
        return player;
    }

    private PlayerSwapHandItemsEvent swap(Player player) {
        PlayerSwapHandItemsEvent event = mock(PlayerSwapHandItemsEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @SuppressWarnings("unchecked")
    private void forceRules(Player player) throws Exception {
        var field = NoticeFlowService.class.getDeclaredField("sessions");
        field.setAccessible(true);
        var sessions = (Map<UUID, NoticeSession>) field.get(flows);
        assertNotNull(sessions.get(player.getUniqueId()), "Expected a real notice session");
        sessions.get(player.getUniqueId()).forcePhase(FlowPhase.RULES);
    }

    private PluginConfiguration configuration() {
        return new PluginConfiguration(
                new MainConfig("test", 30,
                        new MainConfig.MariaDbConfig("jdbc:mariadb://localhost/test", "u", "p", "kudn_", 2, 5000, 15000),
                        new MainConfig.RedisConfig(false, "redis://localhost", "kudn:", 300),
                        new MainConfig.ClientConfig(771, true)),
                new RulesConfig(true, "t", List.of("b"), "c", "l",
                        new RulesConfig.Button("a", "a"), new RulesConfig.Button("r", "r"),
                        new RulesConfig.RejectionConfirmation("t", "b", "x", "y")),
                new ChangelogConfig(true, 1, "v", "t", List.of("b"), "a", "a"),
                new MessagesConfig("", "", "", "", "", "", "", "", "", "", ""));
    }
}
