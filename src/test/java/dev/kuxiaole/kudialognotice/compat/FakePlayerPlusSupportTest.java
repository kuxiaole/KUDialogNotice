package dev.kuxiaole.kudialognotice.compat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FakePlayerPlusSupportTest {
    public interface Api { Registry getFakePlayerManager(); }
    public static final class Registry {
        public final ConcurrentHashMap<UUID, Bot> players = new ConcurrentHashMap<>();
        public Bot get(UUID id) { return players.get(id); }
    }
    public record Bot(Player player) {
        public Player getPlayer() { return player; }
    }

    public static Plugin owner(Registry registry) {
        Plugin owner = mock(Plugin.class, withSettings().extraInterfaces(Api.class));
        when(owner.getName()).thenReturn("FakePlayerPlus");
        when(owner.isEnabled()).thenReturn(true);
        when(((Api) owner).getFakePlayerManager()).thenReturn(registry);
        return owner;
    }

    public static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test void exemptsOnlyExactRegisteredInstance() {
        Registry registry = new Registry();
        Player bot = player(UUID.randomUUID());
        Player sameUuid = player(bot.getUniqueId());
        registry.players.put(bot.getUniqueId(), new Bot(bot));
        Plugin owner = owner(registry);
        FakePlayerPlusSupport support = new FakePlayerPlusSupport(() -> owner, failure -> fail(failure));
        assertTrue(support.isFakePlayer(bot));
        assertFalse(support.isFakePlayer(sameUuid));
        assertFalse(support.isFakePlayer(player(UUID.randomUUID())));
        registry.players.clear();
        assertFalse(support.isFakePlayer(bot));
    }

    @Test void absentDisabledAndOtherPluginsDoNotExempt() {
        Registry registry = new Registry();
        Player bot = player(UUID.randomUUID());
        registry.players.put(bot.getUniqueId(), new Bot(bot));
        AtomicReference<Plugin> current = new AtomicReference<>();
        FakePlayerPlusSupport support = new FakePlayerPlusSupport(current::get, failure -> fail(failure));
        assertFalse(support.isFakePlayer(bot));
        Plugin owner = owner(registry);
        current.set(owner);
        when(owner.isEnabled()).thenReturn(false);
        assertFalse(support.isFakePlayer(bot));
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("SomeOtherPlugin");
        assertFalse(support.isFakePlayer(bot));
    }

    @Test void incompatibleApiFailsClosedAndReportsOnce() {
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("FakePlayerPlus");
        when(owner.isEnabled()).thenReturn(true);
        AtomicInteger warnings = new AtomicInteger();
        FakePlayerPlusSupport support = new FakePlayerPlusSupport(() -> owner, failure -> warnings.incrementAndGet());
        Player player = player(UUID.randomUUID());
        assertFalse(support.isFakePlayer(player));
        assertFalse(support.isFakePlayer(player));
        assertEquals(1, warnings.get());
    }

    @Test void throwingApiFailsClosedAndCanRecover() {
        Registry registry = new Registry();
        Plugin owner = owner(registry);
        Player bot = player(UUID.randomUUID());
        registry.players.put(bot.getUniqueId(), new Bot(bot));
        when(((Api) owner).getFakePlayerManager()).thenThrow(new IllegalStateException("not ready"));
        AtomicInteger warnings = new AtomicInteger();
        FakePlayerPlusSupport support = new FakePlayerPlusSupport(() -> owner, failure -> warnings.incrementAndGet());
        assertFalse(support.isFakePlayer(bot));
        doReturn(registry).when((Api) owner).getFakePlayerManager();
        assertTrue(support.isFakePlayer(bot));
        assertEquals(1, warnings.get());
    }

    @Test void reloadAndSameUuidReplacementAreNotCached() {
        UUID id = UUID.randomUUID();
        Player first = player(id);
        Player replacement = player(id);
        Registry oldRegistry = new Registry();
        oldRegistry.players.put(id, new Bot(first));
        AtomicReference<Plugin> current = new AtomicReference<>(owner(oldRegistry));
        FakePlayerPlusSupport support = new FakePlayerPlusSupport(current::get, failure -> fail(failure));
        assertTrue(support.isFakePlayer(first));
        Registry newRegistry = new Registry();
        newRegistry.players.put(id, new Bot(replacement));
        current.set(owner(newRegistry));
        assertFalse(support.isFakePlayer(first));
        assertTrue(support.isFakePlayer(replacement));
    }
}
