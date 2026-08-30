package dev.kuxiaole.kudialognotice;

import dev.kuxiaole.kudialognotice.client.ClientProtocolResolver;
import dev.kuxiaole.kudialognotice.config.ChangelogDocument;
import dev.kuxiaole.kudialognotice.config.ConfigurationException;
import dev.kuxiaole.kudialognotice.config.ConfigurationLoader;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.command.KUDialogNoticeCommand;
import dev.kuxiaole.kudialognotice.flow.NoticeFlowService;
import dev.kuxiaole.kudialognotice.listener.AuthMeSessionListener;
import dev.kuxiaole.kudialognotice.listener.DialogClickListener;
import dev.kuxiaole.kudialognotice.listener.FallbackGuardListener;
import dev.kuxiaole.kudialognotice.scheduler.SchedulerFacade;
import dev.kuxiaole.kudialognotice.storage.ChangelogSynchronizer;
import dev.kuxiaole.kudialognotice.storage.DistributedStateStore;
import dev.kuxiaole.kudialognotice.text.TextRenderer;
import dev.kuxiaole.kudialognotice.ui.NoticePresenter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class KUDialogNoticePlugin extends JavaPlugin {
    private final AtomicReference<PluginConfiguration> configuration = new AtomicReference<>();
    private final Set<DistributedStateStore> managedStores = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private SchedulerFacade scheduler;
    private volatile DistributedStateStore stateStore;
    private volatile ChangelogSynchronizer changelogSynchronizer;
    private TextRenderer textRenderer;
    private NoticeFlowService flowService;
    private ClientProtocolResolver protocolResolver;
    private CompletableFuture<Void> reloadInProgress;

    @Override
    public void onEnable() {
        ConfigurationLoader loader = new ConfigurationLoader(this);
        try {
            loader.installDefaults();
            configuration.set(loader.load());
        } catch (ConfigurationException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Cannot load KUDialogNotice configuration", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scheduler = new SchedulerFacade(this);
        textRenderer = new TextRenderer();
        stateStore = new DistributedStateStore(this, scheduler, configuration.get().main());
        managedStores.add(stateStore);
        PluginConfiguration initialConfiguration = configuration.get();
        ChangelogSynchronizer initialSynchronizer = newSynchronizer(stateStore, initialConfiguration);
        changelogSynchronizer = initialSynchronizer;
        protocolResolver = new ClientProtocolResolver(this);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, ClientProtocolResolver.VIA_PROXY_DETAILS_CHANNEL, protocolResolver);
        NoticePresenter presenter = new NoticePresenter(textRenderer, protocolResolver);
        flowService = new NoticeFlowService(this, presenter);
        getServer().getPluginManager().registerEvents(
                new AuthMeSessionListener(flowService, protocolResolver), this);
        getServer().getPluginManager().registerEvents(new DialogClickListener(this, flowService), this);
        getServer().getPluginManager().registerEvents(new FallbackGuardListener(flowService), this);
        KUDialogNoticeCommand command = new KUDialogNoticeCommand(this);
        Objects.requireNonNull(getCommand("kudialognotice"), "kudialognotice command")
                .setExecutor(command);
        Objects.requireNonNull(getCommand("kudialognotice"), "kudialognotice command")
                .setTabCompleter(command);
        CompletableFuture<Void> storageInitialization = stateStore.initialize();
        // Start immediately after requesting initialization. The store shares
        // the same initialization future, so the first reconcile waits for the
        // schema without leaving a window in which /reload can race an
        // unstarted synchronizer.
        initialSynchronizer.start(initialConfiguration.changelogDocument())
                .whenComplete((document, syncFailure) -> {
                    if (syncFailure != null && !stopping.get()) {
                        getLogger().log(Level.WARNING,
                                "Initial changelog synchronization is pending; will retry", syncFailure);
                    }
                });
        storageInitialization.whenComplete((ignored, throwable) -> {
            if (stopping.get()) {
                return;
            }
            if (throwable == null) {
                getLogger().info("MariaDB schema is ready; Redis cache will be used when available");
            } else {
                getLogger().log(Level.SEVERE,
                        "State storage initialization failed; rule checks will fail closed until it recovers", throwable);
            }
        });

        getLogger().info("KUDialogNotice enabled (Folia=" + SchedulerFacade.isFolia() + ")");
    }

    @Override
    public void onDisable() {
        stopping.set(true);
        if (flowService != null) {
            flowService.shutdown();
        }
        ChangelogSynchronizer synchronizer = changelogSynchronizer;
        changelogSynchronizer = null;
        if (synchronizer != null) {
            synchronizer.close();
        }
        managedStores.forEach(store -> {
            try {
                store.close();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Cannot close a KUDialogNotice state store cleanly", exception);
            }
        });
        managedStores.clear();
        if (protocolResolver != null) {
            getServer().getMessenger().unregisterIncomingPluginChannel(
                    this, ClientProtocolResolver.VIA_PROXY_DETAILS_CHANNEL, protocolResolver);
        }
    }

    public PluginConfiguration configuration() {
        return configuration.get();
    }

    public SchedulerFacade scheduler() {
        return scheduler;
    }

    public DistributedStateStore stateStore() {
        return stateStore;
    }

    public ChangelogSynchronizer changelogSynchronizer() {
        return changelogSynchronizer;
    }

    public TextRenderer textRenderer() {
        return textRenderer;
    }

    public NoticeFlowService flowService() {
        return flowService;
    }

    public ClientProtocolResolver protocolResolver() {
        return protocolResolver;
    }

    public synchronized CompletableFuture<Void> reloadPlugin() {
        if (stopping.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin is stopping"));
        }
        if (reloadInProgress != null && !reloadInProgress.isDone()) {
            return reloadInProgress;
        }
        ConfigurationLoader loader = new ConfigurationLoader(this);
        PluginConfiguration oldConfiguration = configuration.get();
        DistributedStateStore oldStore = stateStore;
        ChangelogSynchronizer currentSynchronizer = changelogSynchronizer;
        CompletableFuture<Void> reload = scheduler.supplyAsync(() -> {
            try {
                return loader.load();
            } catch (ConfigurationException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }).thenCompose(newConfiguration -> {
            if (stopping.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Plugin is stopping"));
            }
            boolean sameStorage = oldConfiguration.main().mariaDb().equals(newConfiguration.main().mariaDb())
                    && oldConfiguration.main().redis().equals(newConfiguration.main().redis());
            boolean reuseSynchronizer = sameStorage
                    && currentSynchronizer != null
                    && oldConfiguration.main().serverId().equals(newConfiguration.main().serverId());

            // The existing synchronizer serializes this reload with Redis
            // callbacks and polling. This is the common path for editing
            // changelog.yml and avoids replacing a live Redis subscription.
            if (reuseSynchronizer) {
                return currentSynchronizer.reconcileCandidate(newConfiguration.changelogDocument())
                        .thenAccept(authoritative -> {
                            if (stopping.get()) {
                                throw new CompletionException(new IllegalStateException("Plugin is stopping"));
                            }
                            installReloadConfiguration(newConfiguration, authoritative);
                        });
            }

            DistributedStateStore replacement = sameStorage
                    ? oldStore
                    : new DistributedStateStore(this, scheduler, newConfiguration.main());
            boolean ownsReplacement = replacement != oldStore;
            if (ownsReplacement) {
                managedStores.add(replacement);
            }
            ChangelogSynchronizer replacementSynchronizer = newSynchronizer(replacement, newConfiguration);
            AtomicBoolean switched = new AtomicBoolean();
            return replacement.initialize()
                    .thenCompose(ignored -> replacementSynchronizer.start(newConfiguration.changelogDocument()))
                    .thenAccept(authoritative -> {
                        if (stopping.get()) {
                            throw new CompletionException(new IllegalStateException("Plugin is stopping"));
                        }
                        DistributedStateStore previous = stateStore;
                        ChangelogSynchronizer previousSynchronizer = changelogSynchronizer;
                        stateStore = replacement;
                        changelogSynchronizer = replacementSynchronizer;
                        // Mark the replacement live before retiring anything.
                        // A logging/retirement failure must never tear down the
                        // newly selected store and synchronizer.
                        switched.set(true);
                        installReloadConfiguration(newConfiguration, authoritative);
                        if (previousSynchronizer != null && previousSynchronizer != replacementSynchronizer) {
                            try {
                                previousSynchronizer.close();
                            } catch (RuntimeException exception) {
                                getLogger().log(Level.WARNING,
                                        "Cannot close the previous changelog synchronizer cleanly", exception);
                            }
                        }
                        if (previous != replacement) {
                            long retirementDelayMillis = oldConfiguration.main().mariaDb().connectionTimeoutMillis()
                                    + oldConfiguration.main().mariaDb().socketTimeoutMillis()
                                    + 5_000L;
                            scheduler.runAsyncDelayed(() -> {
                                try {
                                    previous.close();
                                } finally {
                                    managedStores.remove(previous);
                                }
                            }, retirementDelayMillis, TimeUnit.MILLISECONDS);
                        }
                    })
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null && !switched.get()) {
                            // A same-storage fallback owns only the new
                            // synchronizer; the existing store must remain
                            // usable by the old configuration.
                            try {
                                replacementSynchronizer.close();
                            } catch (RuntimeException exception) {
                                getLogger().log(Level.WARNING,
                                        "Cannot close the failed replacement synchronizer cleanly", exception);
                            }
                            if (ownsReplacement) {
                                try {
                                    replacement.close();
                                } catch (RuntimeException exception) {
                                    getLogger().log(Level.WARNING,
                                            "Cannot close the failed replacement store cleanly", exception);
                                } finally {
                                    managedStores.remove(replacement);
                                }
                            }
                        }
                    });
        });
        reloadInProgress = reload;
        reload.whenComplete((ignored, throwable) -> {
            synchronized (this) {
                if (reloadInProgress == reload) {
                    reloadInProgress = null;
                }
            }
        });
        return reload;
    }

    private ChangelogSynchronizer newSynchronizer(
            DistributedStateStore store,
            PluginConfiguration candidate
    ) {
        AtomicReference<ChangelogSynchronizer> owner = new AtomicReference<>();
        ChangelogSynchronizer synchronizer = new ChangelogSynchronizer(
                this,
                scheduler,
                store,
                new ConfigurationLoader(this),
                candidate.main(),
                document -> applySynchronizedDocument(owner.get(), document));
        owner.set(synchronizer);
        return synchronizer;
    }

    /**
     * Install a reload's non-changelog settings while preserving a changelog
     * revision that may have arrived through Redis during the reload.
     */
    private void installReloadConfiguration(
            PluginConfiguration candidate,
            ChangelogDocument authoritative
    ) {
        for (;;) {
            PluginConfiguration activeConfiguration = configuration.get();
            ChangelogDocument activeDocument = activeConfiguration.changelogDocument();
            ChangelogDocument selected = authoritative;
            if (authoritative.revision() < activeDocument.revision()) {
                selected = activeDocument;
            } else if (authoritative.revision() == activeDocument.revision()
                    && !authoritative.hasSameContent(activeDocument)) {
                getLogger().warning("Ignoring conflicting changelog reload callback at revision "
                        + authoritative.revision());
                selected = activeDocument;
            }
            PluginConfiguration next = candidate.withChangelog(selected);
            if (configuration.compareAndSet(activeConfiguration, next)) {
                return;
            }
        }
    }

    private void applySynchronizedDocument(
            ChangelogSynchronizer source,
            ChangelogDocument document
    ) {
        if (source == null || document == null || stopping.get()) {
            return;
        }
        scheduler.runGlobal(() -> {
            if (stopping.get() || changelogSynchronizer != source) {
                return;
            }
            // A delayed global-region callback must not downgrade a document
            // that was accepted by a later reload or event.
            for (;;) {
                PluginConfiguration current = configuration.get();
                ChangelogDocument active = current.changelogDocument();
                if (document.revision() < active.revision()) {
                    return;
                }
                if (document.revision() == active.revision()) {
                    if (!document.hasSameContent(active)) {
                        getLogger().warning("Ignoring conflicting changelog callback at revision "
                                + document.revision());
                    }
                    return;
                }
                if (configuration.compareAndSet(current, current.withChangelog(document))) {
                    return;
                }
            }
        });
    }
}
