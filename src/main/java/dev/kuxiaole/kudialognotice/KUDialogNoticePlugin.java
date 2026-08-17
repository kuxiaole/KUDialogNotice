package dev.kuxiaole.kudialognotice;

import dev.kuxiaole.kudialognotice.client.ClientProtocolResolver;
import dev.kuxiaole.kudialognotice.config.ConfigurationException;
import dev.kuxiaole.kudialognotice.config.ConfigurationLoader;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.command.KUDialogNoticeCommand;
import dev.kuxiaole.kudialognotice.flow.NoticeFlowService;
import dev.kuxiaole.kudialognotice.listener.AuthMeSessionListener;
import dev.kuxiaole.kudialognotice.listener.DialogClickListener;
import dev.kuxiaole.kudialognotice.listener.FallbackGuardListener;
import dev.kuxiaole.kudialognotice.scheduler.SchedulerFacade;
import dev.kuxiaole.kudialognotice.storage.DistributedStateStore;
import dev.kuxiaole.kudialognotice.text.TextRenderer;
import dev.kuxiaole.kudialognotice.ui.NoticePresenter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
        stateStore.initialize().whenComplete((ignored, throwable) -> {
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
        reloadInProgress = scheduler.supplyAsync(() -> {
            try {
                return loader.load();
            } catch (ConfigurationException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }).thenCompose(newConfiguration -> {
            if (stopping.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Plugin is stopping"));
            }
            PluginConfiguration oldConfiguration = configuration.get();
            if (oldConfiguration.main().mariaDb().equals(newConfiguration.main().mariaDb())
                    && oldConfiguration.main().redis().equals(newConfiguration.main().redis())) {
                if (stopping.get()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Plugin is stopping"));
                }
                configuration.set(newConfiguration);
                return CompletableFuture.completedFuture(null);
            }

            DistributedStateStore replacement = new DistributedStateStore(this, scheduler, newConfiguration.main());
            managedStores.add(replacement);
            return replacement.initialize().handle((ignored, throwable) -> {
                if (throwable != null || stopping.get()) {
                    replacement.close();
                    managedStores.remove(replacement);
                    if (throwable != null) {
                        throw new IllegalStateException("New storage connection failed", throwable);
                    }
                    throw new IllegalStateException("Plugin is stopping");
                }

                DistributedStateStore previous = stateStore;
                stateStore = replacement;
                configuration.set(newConfiguration);
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
                return null;
            });
        });
        return reloadInProgress;
    }
}
