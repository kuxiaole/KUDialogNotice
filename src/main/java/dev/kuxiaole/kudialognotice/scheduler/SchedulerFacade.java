package dev.kuxiaole.kudialognotice.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class SchedulerFacade {
    private static final boolean FOLIA = detectFolia();

    private final Plugin plugin;

    public SchedulerFacade(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public boolean isEntityContext(Entity entity) {
        return FOLIA ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    public void runAtEntity(Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        if (isEntityContext(entity)) {
            task.run();
            return;
        }
        if (FOLIA) {
            entity.getScheduler().run(plugin, ignored -> task.run(), retired);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtEntityDelayed(Entity entity, Runnable task, Runnable retired, long ticks) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        long safeTicks = Math.max(1L, ticks);
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, ignored -> task.run(), retired, safeTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeTicks);
        }
    }

    public void runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        };
        try {
            if (FOLIA) {
                Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public CompletableFuture<Void> runAsync(ThrowingRunnable runnable) {
        return supplyAsync(() -> {
            try {
                runnable.run();
                return null;
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public void runAsyncDelayed(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(), delay, unit);
        } else {
            long ticks = Math.max(1L, unit.toMillis(delay) / 50L);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
