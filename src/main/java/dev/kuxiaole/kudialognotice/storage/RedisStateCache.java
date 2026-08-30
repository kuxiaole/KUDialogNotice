package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.MainConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class RedisStateCache implements AutoCloseable {
    private final JedisPooled jedis;
    private final URI redisUri;
    private final String prefix;
    private final String changelogChannel;
    private final int ttlSeconds;
    private final ExecutorService subscriptionExecutor;
    private final AtomicReference<Subscription> activeSubscription = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object subscriptionLock = new Object();

    RedisStateCache(MainConfig.RedisConfig config, String namespace) {
        redisUri = URI.create(config.uri());
        jedis = new JedisPooled(redisUri);
        prefix = config.keyPrefix() + "cache:" + namespace + ':';
        changelogChannel = config.keyPrefix() + "events:" + namespace + ":changelog";
        ttlSeconds = config.cacheTtlSeconds();
        subscriptionExecutor = Executors.newSingleThreadExecutor(new SubscriberThreadFactory());
    }

    void ping() {
        jedis.ping();
    }

    Optional<PlayerNoticeState> get(UUID uniqueId, String streamId) {
        List<String> values = jedis.mget(rulesKey(uniqueId), changelogKey(uniqueId, streamId));
        if (values.size() != 2 || values.get(0) == null || values.get(1) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PlayerNoticeState("1".equals(values.get(0)), Long.parseLong(values.get(1))));
        } catch (NumberFormatException exception) {
            delete(uniqueId, streamId);
            return Optional.empty();
        }
    }

    void put(UUID uniqueId, String streamId, PlayerNoticeState state) {
        if (state.rulesAccepted()) {
            jedis.setex(rulesKey(uniqueId), ttlSeconds, "1");
        } else {
            jedis.del(rulesKey(uniqueId));
        }
        jedis.setex(changelogKey(uniqueId, streamId), ttlSeconds, Long.toString(state.lastSeenRevision()));
    }

    void setRulesAccepted(UUID uniqueId) {
        jedis.setex(rulesKey(uniqueId), ttlSeconds, "1");
    }

    void setSeenRevision(UUID uniqueId, String streamId, long revision) {
        String key = changelogKey(uniqueId, streamId);
        String script = "local v=redis.call('GET',KEYS[1]); "
                + "if (not v) or (tonumber(v)<tonumber(ARGV[1])) then "
                + "redis.call('SETEX',KEYS[1],ARGV[2],ARGV[1]); return 1 else return 0 end";
        jedis.eval(script, List.of(key), List.of(Long.toString(revision), Integer.toString(ttlSeconds)));
    }

    /**
     * Publish a small invalidation event. The document itself remains in
     * MariaDB; subscribers should use the event only as a prompt to fetch it.
     */
    void publishChangelogEvent(String channel, String message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        ensureOpen();
        jedis.publish(channel, message);
    }

    /**
     * Start (or replace) a reconnecting subscription on a dedicated Redis
     * connection. The callback runs on the subscriber thread and must not
     * touch live Bukkit state directly.
     */
    void startChangelogSubscription(String channel, Consumer<String> messageConsumer) {
        startChangelogSubscriptionOwned(channel, messageConsumer);
    }

    /**
     * Start a subscription owned by a caller-visible lifecycle token. The
     * returned token can only stop the subscription it created.
     */
    ChangelogSubscription startChangelogSubscriptionOwned(
            String channel,
            Consumer<String> messageConsumer
    ) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(messageConsumer, "messageConsumer");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        ensureOpen();

        ChangelogSubscription owner = new ChangelogSubscription(this::stopChangelogSubscription);
        Subscription next = new Subscription(channel, messageConsumer, owner);
        synchronized (subscriptionLock) {
            ensureOpen();
            Subscription previous = activeSubscription.getAndSet(next);
            if (previous != null) {
                previous.stop();
            }
            try {
                next.task = subscriptionExecutor.submit(() -> runSubscription(next));
            } catch (RuntimeException exception) {
                activeSubscription.compareAndSet(next, null);
                owner.closeFromCache();
                throw exception;
            }
        }
        return owner;
    }

    /** Stop the current subscription without shutting down the reusable executor. */
    void stopChangelogSubscription() {
        Subscription previous;
        synchronized (subscriptionLock) {
            previous = activeSubscription.getAndSet(null);
        }
        if (previous != null) {
            previous.stop();
        }
    }

    /** Stop only the subscription currently owned by {@code owner}. */
    void stopChangelogSubscription(ChangelogSubscription owner) {
        Objects.requireNonNull(owner, "owner");
        Subscription previous = null;
        synchronized (subscriptionLock) {
            Subscription active = activeSubscription.get();
            if (active != null && active.owner == owner
                    && activeSubscription.compareAndSet(active, null)) {
                previous = active;
            }
        }
        if (previous != null) {
            previous.stop();
        }
    }

    String changelogChannel() {
        return changelogChannel;
    }

    private void runSubscription(Subscription subscription) {
        long backoffMillis = 250L;
        while (subscription.isRunning() && !closed.get()) {
            Jedis connection = null;
            try {
                connection = new Jedis(redisUri);
                subscription.connection = connection;
                if (!subscription.isRunning() || closed.get()) {
                    return;
                }

                Jedis connectionForCallback = connection;
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String subscribedChannel, String message) {
                        if (!subscription.isRunning() || closed.get()
                                || activeSubscription.get() != subscription) {
                            return;
                        }
                        try {
                            // The owner check is repeated immediately before
                            // invoking user code to reject a replaced owner
                            // whenever the stop/reconnect race permits.
                            if (!subscription.isRunning() || closed.get()
                                    || activeSubscription.get() != subscription) {
                                return;
                            }
                            subscription.messageConsumer.accept(message);
                        } catch (RuntimeException ignored) {
                            // A bad consumer must not permanently kill the
                            // Redis subscription; the next event can still be
                            // delivered and the periodic DB reconcile is the
                            // final consistency fallback.
                        }
                    }
                };
                subscription.pubSub = pubSub;
                connectionForCallback.subscribe(pubSub, subscription.channel);
                backoffMillis = 250L;
            } catch (RuntimeException ignored) {
                // Connection failures are expected during a Redis restart;
                // reconnect with bounded exponential backoff below.
            } finally {
                subscription.pubSub = null;
                subscription.connection = null;
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (RuntimeException ignored) {
                        // Preserve the subscription loop's termination path.
                    }
                }
            }

            if (!subscription.isRunning() || closed.get()) {
                return;
            }
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMillis = Math.min(backoffMillis * 2L, 30_000L);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Redis state cache is closed");
        }
    }

    private void delete(UUID uniqueId, String streamId) {
        jedis.del(rulesKey(uniqueId), changelogKey(uniqueId, streamId));
    }

    private String rulesKey(UUID uniqueId) {
        return prefix + "rules:" + uniqueId;
    }

    private String changelogKey(UUID uniqueId, String streamId) {
        return prefix + "changelog:" + streamId + ':' + uniqueId;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopChangelogSubscription();
        subscriptionExecutor.shutdownNow();
        try {
            subscriptionExecutor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        jedis.close();
    }

    private static final class Subscription {
        private final String channel;
        private final Consumer<String> messageConsumer;
        private final ChangelogSubscription owner;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Jedis connection;
        private volatile JedisPubSub pubSub;
        private volatile Future<?> task;

        private Subscription(
                String channel,
                Consumer<String> messageConsumer,
                ChangelogSubscription owner
        ) {
            this.channel = channel;
            this.messageConsumer = messageConsumer;
            this.owner = owner;
        }

        private boolean isRunning() {
            return running.get() && owner.isOpen();
        }

        private void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            owner.closeFromCache();
            JedisPubSub currentPubSub = pubSub;
            if (currentPubSub != null) {
                try {
                    currentPubSub.unsubscribe();
                } catch (RuntimeException ignored) {
                    // Closing the dedicated connection below also unblocks
                    // Jedis when unsubscribe cannot reach Redis.
                }
            }
            Jedis currentConnection = connection;
            if (currentConnection != null) {
                try {
                    currentConnection.close();
                } catch (RuntimeException ignored) {
                    // Best effort; the task is cancelled as a final guard.
                }
            }
            Future<?> currentTask = task;
            if (currentTask != null) {
                currentTask.cancel(true);
            }
        }
    }

    private static final class SubscriberThreadFactory implements ThreadFactory {
        private static final AtomicInteger IDS = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "KUDialogNotice-Redis-Subscriber-" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
