package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.ChangelogCodec;
import dev.kuxiaole.kudialognotice.config.ChangelogDocument;
import dev.kuxiaole.kudialognotice.config.ConfigurationException;
import dev.kuxiaole.kudialognotice.config.ConfigurationLoader;
import dev.kuxiaole.kudialognotice.config.MainConfig;
import dev.kuxiaole.kudialognotice.scheduler.SchedulerFacade;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Coordinates the local changelog file, MariaDB authority and Redis
 * invalidation messages. All database and file work is delegated to the
 * scheduler's asynchronous executor; the only state shared with Bukkit is the
 * immutable document delivered to {@code appliedListener}.
 */
public final class ChangelogSynchronizer implements AutoCloseable {
    public static final String NOTICE_ID = "global";
    private static final long POLL_INITIAL_DELAY_SECONDS = 30L;
    private static final long POLL_INTERVAL_SECONDS = 30L;
    /** Only one synchronizer should ever replace this plugin's changelog file. */
    private static final Object FILE_WRITE_LOCK = new Object();

    private final JavaPlugin plugin;
    private final SchedulerFacade scheduler;
    private final DistributedStateStore store;
    private final ConfigurationLoader loader;
    private final Path changelogFile;
    private final String sourceServerId;
    private final String namespace;
    private final UUID sourceNodeId;
    private final Consumer<ChangelogDocument> appliedListener;
    private final ScheduledExecutorService timer;
    private final AtomicReference<ChangelogDocument> current = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> operationTail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final AtomicBoolean syncRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile ChangelogSubscription subscription;
    private volatile ScheduledFuture<?> pollTask;
    private boolean started;

    public ChangelogSynchronizer(
            JavaPlugin plugin,
            SchedulerFacade scheduler,
            DistributedStateStore store,
            ConfigurationLoader loader,
            MainConfig mainConfig,
            Consumer<ChangelogDocument> appliedListener
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.store = Objects.requireNonNull(store, "store");
        this.loader = Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(mainConfig, "mainConfig");
        this.changelogFile = plugin.getDataFolder().toPath().resolve("changelog.yml");
        this.sourceServerId = Objects.requireNonNull(mainConfig.serverId(), "serverId");
        this.namespace = namespaceFor(mainConfig.mariaDb());
        this.sourceNodeId = UUID.randomUUID();
        this.appliedListener = Objects.requireNonNull(appliedListener, "appliedListener");
        this.timer = Executors.newSingleThreadScheduledExecutor(namedDaemon("KUDialogNotice-changelog"));
    }

    /**
     * Start Redis invalidation and periodic reconciliation, then publish or
     * adopt the supplied local document. A failed first database attempt is
     * intentionally left retryable by the periodic task.
     */
    public CompletableFuture<ChangelogDocument> start(ChangelogDocument local) {
        Objects.requireNonNull(local, "local");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Changelog synchronizer is closed"));
            }
            current.compareAndSet(null, local);
            if (!started) {
                started = true;
                startRedisSubscription();
                pollTask = timer.scheduleAtFixedRate(
                        this::requestDatabaseSync,
                        POLL_INITIAL_DELAY_SECONDS,
                        POLL_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
            }
        }
        return reconcileCandidate(local);
    }

    /**
     * Reconcile a freshly loaded local file. Calls are serialized with Redis
     * callbacks and polling so an older completion can never overwrite a newer
     * document.
     */
    public CompletableFuture<ChangelogDocument> reconcileCandidate(ChangelogDocument candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return enqueue(() -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Changelog synchronizer is closed"));
            }
            return store.reconcileChangelogDocument(
                            NOTICE_ID,
                            candidate.revision(),
                            candidate.canonicalPayload(),
                            candidate.sha256(),
                            sourceServerId)
                    .thenCompose(result -> handleReconcileResult(candidate, result, true));
        });
    }

    public ChangelogDocument currentDocument() {
        return current.get();
    }

    public UUID sourceNodeId() {
        return sourceNodeId;
    }

    public String namespace() {
        return namespace;
    }

    /** Trigger a best-effort database refresh (used by tests and lifecycle code). */
    public void refreshNow() {
        requestDatabaseSync();
    }

    private void startRedisSubscription() {
        if (!store.hasRedis()) {
            return;
        }
        String channel = store.changelogChannel();
        if (channel == null || channel.isBlank()) {
            return;
        }
        try {
            AtomicReference<ChangelogSubscription> ownerReference = new AtomicReference<>();
            ChangelogSubscription owner = store.startChangelogSubscriptionOwned(
                    channel,
                    message -> {
                        ChangelogSubscription currentOwner = ownerReference.get();
                        if (currentOwner != null && currentOwner.isOpen()) {
                            onRedisMessage(message);
                        }
                    });
            ownerReference.set(owner);
            subscription = owner;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot start Redis changelog subscription; periodic MariaDB sync remains enabled", exception);
        }
    }

    private void onRedisMessage(String rawMessage) {
        if (closed.get()) {
            return;
        }
        Optional<ChangelogEvent> parsed = ChangelogEventCodec.decode(rawMessage);
        if (parsed.isEmpty()) {
            plugin.getLogger().fine("Ignoring malformed KUDialogNotice changelog event");
            return;
        }
        ChangelogEvent event = parsed.get();
        if (!namespace.equals(event.namespace())
                || !NOTICE_ID.equals(event.noticeId())
                || sourceNodeId.equals(event.sourceNodeId())) {
            return;
        }
        ChangelogDocument local = current.get();
        if (local != null) {
            if (event.revision() < local.revision()) {
                return;
            }
            if (event.revision() == local.revision()
                    && event.sha256().equalsIgnoreCase(local.sha256())) {
                return;
            }
        }
        requestDatabaseSync();
    }

    private void requestDatabaseSync() {
        if (closed.get() || !syncRequested.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> operation = enqueue(() -> {
            if (closed.get()) {
                return CompletableFuture.completedFuture(null);
            }
            ChangelogDocument candidate = current.get();
            if (candidate == null) {
                return CompletableFuture.completedFuture(null);
            }
            return store.reconcileChangelogDocument(
                            NOTICE_ID,
                            candidate.revision(),
                            candidate.canonicalPayload(),
                            candidate.sha256(),
                            sourceServerId)
                    .thenCompose(result -> handleReconcileResult(candidate, result, false))
                    .thenAccept(ignored -> { });
        });
        operation.whenComplete((ignored, throwable) -> {
            syncRequested.set(false);
            if (throwable != null && !closed.get()) {
                plugin.getLogger().log(Level.WARNING,
                        "Cannot synchronize changelog from MariaDB; will retry", unwrap(throwable));
            }
        });
    }

    private CompletableFuture<ChangelogDocument> handleReconcileResult(
            ChangelogDocument candidate,
            ChangelogReconcileResult result,
            boolean failOnConflict
    ) {
        StoredChangelogDocument authoritative = result.document();
        if (result.decision() == ChangelogReconcileResult.Decision.CONFLICT) {
            IllegalStateException conflict = new IllegalStateException(
                    "changelog revision " + candidate.revision()
                            + " conflicts with the MariaDB document hash " + authoritative.sha256()
                            + "; increment revision before publishing a changed file");
            if (failOnConflict) {
                return CompletableFuture.failedFuture(conflict);
            }
            plugin.getLogger().log(Level.WARNING, conflict.getMessage());
            return CompletableFuture.completedFuture(candidate);
        }

        boolean same = candidate.revision() == authoritative.revision()
                && candidate.sha256().equalsIgnoreCase(authoritative.sha256())
                && candidate.canonicalPayload().equals(authoritative.canonicalPayload());
        if (same && result.decision() != ChangelogReconcileResult.Decision.REMOTE_NEWER) {
            ChangelogDocument effective = advanceCurrent(candidate);
            if (effective != candidate) {
                return CompletableFuture.completedFuture(effective);
            }
            if (result.changed()) {
                return publish(candidate).thenApply(ignored -> candidate);
            }
            return CompletableFuture.completedFuture(candidate);
        }

        return parseAndApply(authoritative).thenCompose(remote -> {
            // A lower revision can only be returned as REMOTE_NEWER. If the DB
            // returned an unexpected equal/newer payload, applying its verified
            // authoritative bytes is still the least surprising behavior.
            return CompletableFuture.completedFuture(remote);
        });
    }

    /** Advance the in-memory snapshot without ever moving to a lower revision. */
    private ChangelogDocument advanceCurrent(ChangelogDocument document) {
        for (;;) {
            ChangelogDocument previous = current.get();
            if (previous != null && document.revision() < previous.revision()) {
                return previous;
            }
            if (current.compareAndSet(previous, document)) {
                return document;
            }
        }
    }

    private CompletableFuture<ChangelogDocument> parseAndApply(StoredChangelogDocument stored) {
        return scheduler.supplyAsync(() -> {
            try {
                ChangelogDocument parsed = loader.loadChangelogDocument(stored.canonicalPayload());
                if (parsed.revision() != stored.revision()
                        || !parsed.sha256().equalsIgnoreCase(stored.sha256())
                        || !parsed.canonicalPayload().equals(stored.canonicalPayload())) {
                    throw new IllegalStateException("MariaDB changelog payload failed revision/hash verification");
                }
                /*
                 * A storage switch can leave an older synchronizer with an
                 * in-flight read.  Serialize the physical replacement and
                 * inspect the current file so that that stale read cannot
                 * downgrade the shared changelog on disk.
                 */
                synchronized (FILE_WRITE_LOCK) {
                    synchronized (lifecycleLock) {
                        if (closed.get()) {
                            throw new IllegalStateException("Changelog synchronizer is closed");
                        }
                        ChangelogDocument known = current.get();
                        if (known != null && known.revision() > parsed.revision()) {
                            return known;
                        }
                        ChangelogDocument onDisk = readLocalChangelog();
                        if (onDisk != null && onDisk.revision() > parsed.revision()) {
                            return onDisk;
                        }
                        ChangelogCodec.writeAtomically(changelogFile, parsed.canonicalPayload());
                    }
                }
                return parsed;
            } catch (ConfigurationException | java.io.IOException | RuntimeException exception) {
                throw new CompletionException("Cannot apply MariaDB changelog document", exception);
            }
        }).thenApply(parsed -> {
            if (closed.get()) {
                return parsed;
            }

            ChangelogDocument previous = current.get();
            ChangelogDocument effective = advanceCurrent(parsed);
            if (effective != parsed) {
                return effective;
            }
            if (previous == null || !parsed.hasSameContent(previous)) {
                try {
                    appliedListener.accept(parsed);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Changelog was written but its in-memory configuration callback failed", exception);
                }
            }
            return parsed;
        });
    }

    /** Read the current local file while the caller holds FILE_WRITE_LOCK. */
    private ChangelogDocument readLocalChangelog() {
        try {
            if (!Files.exists(changelogFile) || Files.size(changelogFile) > ChangelogCodec.MAX_PAYLOAD_BYTES) {
                return null;
            }
            return loader.loadChangelogDocument(Files.readAllBytes(changelogFile));
        } catch (ConfigurationException | java.io.IOException | RuntimeException ignored) {
            // A malformed or partially edited local file is replaceable by
            // the already-validated MariaDB document.
            return null;
        }
    }

    private CompletableFuture<Void> publish(ChangelogDocument document) {
        if (!store.hasRedis()) {
            return CompletableFuture.completedFuture(null);
        }
        String channel = store.changelogChannel();
        if (channel == null || channel.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        ChangelogEvent event = new ChangelogEvent(
                namespace,
                NOTICE_ID,
                document.revision(),
                document.sha256(),
                sourceServerId,
                sourceNodeId,
                UUID.randomUUID());
        String message = ChangelogEventCodec.encode(event);
        return store.publishChangelogEvent(channel, message).handle((ignored, throwable) -> {
            if (throwable != null && !closed.get()) {
                plugin.getLogger().log(Level.WARNING,
                        "Changelog saved in MariaDB but Redis notification failed; periodic sync remains enabled",
                        unwrap(throwable));
            }
            return null;
        });
    }

    private <T> CompletableFuture<T> enqueue(java.util.function.Supplier<CompletableFuture<T>> operation) {
        synchronized (operationTail) {
            CompletableFuture<Void> previous = operationTail.get().handle((ignored, throwable) -> null);
            CompletableFuture<T> next;
            try {
                next = previous.thenCompose(ignored -> operation.get());
            } catch (RuntimeException exception) {
                next = CompletableFuture.failedFuture(exception);
            }
            operationTail.set(next.handle((ignored, throwable) -> null));
            return next;
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ChangelogSubscription owner = subscription;
            subscription = null;
            if (owner != null) {
                try {
                    owner.close();
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Cannot stop Redis changelog subscription cleanly", exception);
                }
            }
            ScheduledFuture<?> task = pollTask;
            if (task != null) {
                task.cancel(false);
            }
            timer.shutdownNow();
        }
    }

    public static String namespaceFor(MainConfig.MariaDbConfig config) {
        Objects.requireNonNull(config, "config");
        String authority = config.jdbcUrl() + '\0' + config.tablePrefix();
        return UUID.nameUUIDFromBytes(authority.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static ThreadFactory namedDaemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
