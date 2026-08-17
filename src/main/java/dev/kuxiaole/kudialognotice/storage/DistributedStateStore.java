package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.MainConfig;
import dev.kuxiaole.kudialognotice.scheduler.SchedulerFacade;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class DistributedStateStore implements AutoCloseable {
    private final Plugin plugin;
    private final SchedulerFacade scheduler;
    private final MariaDbRepository repository;
    private final RedisStateCache redis;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> initialization = new AtomicReference<>();
    private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

    public DistributedStateStore(Plugin plugin, SchedulerFacade scheduler, MainConfig config) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        MariaDbRepository createdRepository = new MariaDbRepository(config.mariaDb());
        RedisStateCache createdRedis = null;
        try {
            if (config.redis().enabled()) {
                createdRedis = new RedisStateCache(config.redis(), cacheNamespace(config.mariaDb()));
            }
        } catch (RuntimeException | Error exception) {
            try {
                createdRepository.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        repository = createdRepository;
        redis = createdRedis;
    }

    public CompletableFuture<Void> initialize() {
        return ensureReady();
    }

    public CompletableFuture<PlayerNoticeState> load(
            PlayerIdentity player,
            String streamId,
            long currentRevision
    ) {
        return ensureReady().thenCompose(ignored -> scheduler.supplyAsync(() -> {
            Optional<PlayerNoticeState> cached = redisGet(player.uniqueId(), streamId);
            if (cached.isPresent() && cached.get().rulesAccepted()
                    && cached.get().lastSeenRevision() >= currentRevision) {
                return cached.get();
            }
            try {
                PlayerNoticeState state = repository.load(player, streamId);
                redisRun(cache -> cache.put(player.uniqueId(), streamId, state));
                return state;
            } catch (SQLException exception) {
                throw failure("Cannot load player notice state", exception);
            }
        }));
    }

    public CompletableFuture<Void> acceptRules(PlayerIdentity player) {
        return ensureReady().thenCompose(ignored -> scheduler.runAsync(() -> {
            repository.acceptRules(player);
            redisRun(cache -> cache.setRulesAccepted(player.uniqueId()));
        }));
    }

    public CompletableFuture<Void> markChangelogSeen(PlayerIdentity player, String streamId, long revision) {
        return ensureReady().thenCompose(ignored -> scheduler.runAsync(() -> {
            repository.markChangelogSeen(player, streamId, revision);
            redisRun(cache -> cache.setSeenRevision(player.uniqueId(), streamId, revision));
        }));
    }

    public CompletableFuture<Optional<PlayerIdentity>> resolvePlayer(String input) {
        return ensureReady().thenCompose(ignored -> scheduler.supplyAsync(() -> {
            try {
                return repository.resolvePlayer(input);
            } catch (SQLException exception) {
                throw failure("Cannot resolve player", exception);
            }
        }));
    }

    public CompletableFuture<PlayerStatus> loadStatus(PlayerIdentity player, String streamId) {
        return ensureReady().thenCompose(ignored -> scheduler.supplyAsync(() -> {
            try {
                return repository.loadStatus(player, streamId);
            } catch (SQLException exception) {
                throw failure("Cannot load player status", exception);
            }
        }));
    }

    public CompletableFuture<Optional<PlayerStatus>> findStatus(String input, String streamId) {
        return resolvePlayer(input).thenCompose(player -> player
                .map(identity -> loadStatus(identity, streamId).thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    private synchronized CompletableFuture<Void> ensureReady() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("State store is closed"));
        }
        if (ready.get()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> existing = initialization.get();
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> created = scheduler.runAsync(() -> {
            if (closed.get()) {
                throw new IllegalStateException("State store is closed");
            }
            repository.initializeSchema();
            if (redis != null) {
                try {
                    redis.ping();
                    redisWarningLogged.set(false);
                } catch (RuntimeException exception) {
                    logRedisFailure(exception);
                }
            }
            synchronized (this) {
                if (closed.get()) {
                    throw new IllegalStateException("State store was closed during initialization");
                }
                ready.set(true);
            }
        });
        initialization.set(created);
        created.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                initialization.compareAndSet(created, null);
            }
        });
        return created;
    }

    private Optional<PlayerNoticeState> redisGet(UUID uniqueId, String streamId) {
        if (redis == null) {
            return Optional.empty();
        }
        try {
            Optional<PlayerNoticeState> value = redis.get(uniqueId, streamId);
            redisWarningLogged.set(false);
            return value;
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
            return Optional.empty();
        }
    }

    private void redisRun(RedisOperation operation) {
        if (redis == null) {
            return;
        }
        try {
            operation.run(redis);
            redisWarningLogged.set(false);
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
        }
    }

    private void logRedisFailure(RuntimeException exception) {
        if (redisWarningLogged.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING,
                    "Redis is unavailable; KUDialogNotice is using MariaDB directly", exception);
        }
    }

    private static StateStoreException failure(String message, Exception exception) {
        return new StateStoreException(message, exception);
    }

    private static String cacheNamespace(MainConfig.MariaDbConfig config) {
        String authority = config.jdbcUrl() + '\0' + config.tablePrefix();
        return UUID.nameUUIDFromBytes(authority.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready.set(false);
        RuntimeException failure = null;
        try {
            if (redis != null) {
                redis.close();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            repository.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    private interface RedisOperation {
        void run(RedisStateCache cache);
    }
}
