package dev.kuxiaole.kudialognotice.compat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Optional integration: only the exact live Player registered by FakePlayerPlus is exempt. */
public final class FakePlayerPlusSupport {
    private final Supplier<Plugin> lookup;
    private final Consumer<Throwable> reportFailure;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    public FakePlayerPlusSupport(Plugin host) {
        this(() -> host.getServer().getPluginManager().getPlugin("FakePlayerPlus"),
                failure -> host.getLogger().log(Level.WARNING,
                        "Cannot verify FakePlayerPlus identity; normal notice checks remain active", failure));
    }

    FakePlayerPlusSupport(Supplier<Plugin> lookup, Consumer<Throwable> reportFailure) {
        this.lookup = lookup;
        this.reportFailure = reportFailure;
    }

    public boolean isFakePlayer(Player player) {
        try {
            Plugin owner = lookup.get();
            if (owner == null || !owner.isEnabled() || !"FakePlayerPlus".equals(owner.getName())) {
                return false;
            }
            // Use the live API, never the persisted UUID/name list. Do not retain the
            // other plugin, its classes or Player instances across reloads. These
            // calls read an in-memory registry; they do not schedule work or query IO.
            Object manager = owner.getClass().getMethod("getFakePlayerManager").invoke(owner);
            if (manager == null) {
                return false;
            }
            Object bot = manager.getClass().getMethod("get", UUID.class).invoke(manager, player.getUniqueId());
            return bot != null && bot.getClass().getMethod("getPlayer").invoke(bot) == player;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Unknown/incompatible integrations must not let real players bypass consent.
            if (failureReported.compareAndSet(false, true)) {
                reportFailure.accept(failure);
            }
            return false;
        }
    }
}
