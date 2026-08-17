package dev.kuxiaole.kudialognotice.flow;

import dev.kuxiaole.kudialognotice.KUDialogNoticePlugin;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.storage.PlayerIdentity;
import dev.kuxiaole.kudialognotice.storage.PlayerNoticeState;
import dev.kuxiaole.kudialognotice.ui.NoticePresenter;
import dev.kuxiaole.kudialognotice.ui.UserAction;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class NoticeFlowService {
    private final KUDialogNoticePlugin plugin;
    private final NoticePresenter presenter;
    private final ConcurrentHashMap<UUID, NoticeSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public NoticeFlowService(KUDialogNoticePlugin plugin, NoticePresenter presenter) {
        this.plugin = plugin;
        this.presenter = presenter;
    }

    public void onAuthenticated(Player player) {
        if (shuttingDown.get()) {
            return;
        }
        PluginConfiguration configuration = plugin.configuration();
        boolean bypassRules = player.hasPermission("kudialognotice.bypass.rules") || !configuration.rules().enabled();
        boolean bypassChangelog = player.hasPermission("kudialognotice.bypass.changelog")
                || !configuration.changelog().enabled();
        if (bypassRules && bypassChangelog) {
            sessions.remove(player.getUniqueId());
            return;
        }

        NoticeSession session = new NoticeSession(
                new PlayerIdentity(player.getUniqueId(), player.getName()),
                configuration,
                bypassRules,
                bypassChangelog,
                false);
        sessions.put(player.getUniqueId(), session);

        CompletableFuture<PlayerNoticeState> stateFuture = plugin.stateStore().load(
                session.player(),
                configuration.main().serverId(),
                configuration.changelog().enabled() ? configuration.changelog().revision() : 0L);
        CompletableFuture<Void> delayFuture = new CompletableFuture<>();
        plugin.scheduler().runAtEntityDelayed(
                player,
                () -> delayFuture.complete(null),
                () -> delayFuture.completeExceptionally(new IllegalStateException("Player retired")),
                configuration.main().displayDelayTicks());

        stateFuture.thenCombine(delayFuture, (state, ignored) -> state)
                .whenComplete((state, throwable) -> {
                    if (shuttingDown.get()) {
                        return;
                    }
                    plugin.scheduler().runAtEntity(
                            player,
                            () -> finishLoading(player, session, state, throwable),
                            () -> removeIfCurrent(session));
                });
    }

    public boolean startPreview(Player player, String type) {
        PluginConfiguration configuration = plugin.configuration();
        NoticeSession session = new NoticeSession(
                new PlayerIdentity(player.getUniqueId(), player.getName()),
                configuration,
                false,
                false,
                true);
        if (sessions.putIfAbsent(player.getUniqueId(), session) != null) {
            return false;
        }
        if ("rules".equalsIgnoreCase(type)) {
            if (!preparePresentation(player, session)) {
                removeIfCurrent(session);
                return false;
            }
            session.forcePhase(FlowPhase.RULES);
            presenter.showRules(
                    player, session.player(), configuration, session.token(), false, session.fallback());
            return true;
        }
        if ("changelog".equalsIgnoreCase(type)) {
            if (!preparePresentation(player, session)) {
                removeIfCurrent(session);
                return false;
            }
            session.forcePhase(FlowPhase.CHANGELOG);
            presenter.showChangelog(
                    player, session.player(), configuration, session.token(), session.fallback());
            return true;
        }
        sessions.remove(player.getUniqueId(), session);
        return false;
    }

    public void handleNativeAction(Player player, UUID token, UserAction action, Boolean rulesRead) {
        NoticeSession session = sessions.get(player.getUniqueId());
        if (session == null || session.fallback()) {
            return;
        }
        handleAction(player, token, action, Boolean.TRUE.equals(rulesRead));
    }

    public void handleBookAction(Player player, UUID token, UserAction action) {
        NoticeSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.fallback()) {
            return;
        }
        handleAction(player, token, action, true);
    }

    public void cleanup(UUID uniqueId) {
        sessions.remove(uniqueId);
    }

    public boolean isBlocking(UUID uniqueId) {
        NoticeSession session = sessions.get(uniqueId);
        if (session == null || session.preview()) {
            return false;
        }
        return switch (session.phase()) {
            case RULES, SAVING_RULES, REJECTION_CONFIRMATION, CHANGELOG, SAVING_CHANGELOG -> true;
            default -> false;
        };
    }

    public void reopen(Player player) {
        NoticeSession session = sessions.get(player.getUniqueId());
        if (session == null || !isBlocking(player.getUniqueId()) || !session.markReopenScheduled()) {
            return;
        }
        plugin.scheduler().runAtEntityDelayed(player, () -> {
            session.clearReopenScheduled();
            if (!isCurrent(session) || !isBlocking(player.getUniqueId())) {
                return;
            }
            switch (session.phase()) {
                case RULES -> presenter.showRules(
                        player, session.player(), session.configuration(), session.token(), false, session.fallback());
                case REJECTION_CONFIRMATION -> presenter.showRejectionConfirmation(
                        player, session.player(), session.configuration(), session.token(), session.fallback());
                case CHANGELOG -> presenter.showChangelog(
                        player, session.player(), session.configuration(), session.token(), session.fallback());
                default -> { }
            }
        }, () -> removeIfCurrent(session), 1L);
    }

    public void shutdown() {
        shuttingDown.set(true);
        sessions.clear();
    }

    private void finishLoading(
            Player player,
            NoticeSession session,
            PlayerNoticeState state,
            Throwable throwable
    ) {
        if (!isCurrent(session) || !player.isConnected()) {
            removeIfCurrent(session);
            return;
        }
        if (throwable != null) {
            handleLoadFailure(player, session, throwable);
            return;
        }

        session.lastSeenRevision(state.lastSeenRevision());
        FlowPolicy.InitialStep step = FlowPolicy.initialStep(
                !session.bypassRules(),
                state.rulesAccepted(),
                !session.bypassChangelog(),
                state.lastSeenRevision(),
                session.configuration().changelog().revision());
        if (step == FlowPolicy.InitialStep.RULES) {
            showRulesOrRejectClient(player, session);
        } else if (step == FlowPolicy.InitialStep.CHANGELOG) {
            showChangelogOrFinish(player, session, FlowPhase.LOADING);
        } else if (session.transition(FlowPhase.LOADING, FlowPhase.COMPLETE)) {
            removeIfCurrent(session);
        }
    }

    private void handleLoadFailure(Player player, NoticeSession session, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, "Cannot load notice state for " + session.player().name(), throwable);
        if (!session.bypassRules()) {
            session.forcePhase(FlowPhase.COMPLETE);
            removeIfCurrent(session);
            player.kick(plugin.textRenderer().render(session.configuration().messages().databaseUnavailable()));
        } else {
            removeIfCurrent(session);
        }
    }

    private void handleAction(
            Player player,
            UUID token,
            UserAction action,
            boolean rulesRead
    ) {
        NoticeSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.token().equals(token) || !player.isConnected()) {
            return;
        }

        switch (action) {
            case RULES_ACCEPT -> acceptRules(player, session, rulesRead);
            case RULES_REJECT -> rejectRules(player, session, rulesRead);
            case REJECTION_RETURN -> returnToRules(player, session);
            case REJECTION_CONFIRM -> confirmRejection(player, session);
            case CHANGELOG_ACKNOWLEDGE -> acknowledgeChangelog(player, session);
        }
    }

    private void acceptRules(Player player, NoticeSession session, boolean rulesRead) {
        if (session.phase() != FlowPhase.RULES) {
            return;
        }
        if (!rulesRead) {
            sendLockedMessage(player, session);
            return;
        }
        if (session.preview()) {
            completeAndClose(player, session);
            return;
        }
        if (!session.transition(FlowPhase.RULES, FlowPhase.SAVING_RULES)) {
            return;
        }
        plugin.stateStore().acceptRules(session.player()).whenComplete((ignored, throwable) -> {
            if (shuttingDown.get()) {
                return;
            }
            plugin.scheduler().runAtEntity(
                    player,
                    () -> finishRulesSave(player, session, throwable),
                    () -> removeIfCurrent(session));
        });
    }

    private void finishRulesSave(Player player, NoticeSession session, Throwable throwable) {
        if (!isCurrent(session) || session.phase() != FlowPhase.SAVING_RULES || !player.isConnected()) {
            return;
        }
        if (throwable != null) {
            plugin.getLogger().log(Level.SEVERE, "Cannot save rules acceptance for " + session.player().name(), throwable);
            session.forcePhase(FlowPhase.RULES);
            player.sendActionBar(messageWithPrefix(
                    session.configuration(), session.configuration().messages().rulesSaveFailed()));
            presenter.showRules(
                    player, session.player(), session.configuration(), session.token(), true, session.fallback());
            return;
        }
        showChangelogOrFinish(player, session, FlowPhase.SAVING_RULES);
    }

    private void rejectRules(Player player, NoticeSession session, boolean rulesRead) {
        if (session.phase() != FlowPhase.RULES) {
            return;
        }
        if (!rulesRead) {
            sendLockedMessage(player, session);
            return;
        }
        if (session.transition(FlowPhase.RULES, FlowPhase.REJECTION_CONFIRMATION)) {
            presenter.showRejectionConfirmation(
                    player, session.player(), session.configuration(), session.token(), session.fallback());
        }
    }

    private void returnToRules(Player player, NoticeSession session) {
        if (session.transition(FlowPhase.REJECTION_CONFIRMATION, FlowPhase.RULES)) {
            presenter.showRules(
                    player, session.player(), session.configuration(), session.token(), true, session.fallback());
        }
    }

    private void confirmRejection(Player player, NoticeSession session) {
        if (session.phase() != FlowPhase.REJECTION_CONFIRMATION) {
            return;
        }
        if (session.preview()) {
            completeAndClose(player, session);
            return;
        }
        if (!session.transition(FlowPhase.REJECTION_CONFIRMATION, FlowPhase.COMPLETE)) {
            return;
        }
        removeIfCurrent(session);
        presenter.close(player, session.fallback());
        player.kick(plugin.textRenderer().render(session.configuration().messages().rejectedKick()));
    }

    private void acknowledgeChangelog(Player player, NoticeSession session) {
        if (session.phase() != FlowPhase.CHANGELOG) {
            return;
        }
        if (session.preview()) {
            completeAndClose(player, session);
            return;
        }
        if (!session.transition(FlowPhase.CHANGELOG, FlowPhase.SAVING_CHANGELOG)) {
            return;
        }
        PluginConfiguration configuration = session.configuration();
        plugin.stateStore().markChangelogSeen(
                        session.player(), configuration.main().serverId(), configuration.changelog().revision())
                .whenComplete((ignored, throwable) -> {
                    if (shuttingDown.get()) {
                        return;
                    }
                    plugin.scheduler().runAtEntity(
                            player,
                            () -> finishChangelogSave(player, session, throwable),
                            () -> removeIfCurrent(session));
                });
    }

    private void finishChangelogSave(Player player, NoticeSession session, Throwable throwable) {
        if (!isCurrent(session) || session.phase() != FlowPhase.SAVING_CHANGELOG || !player.isConnected()) {
            return;
        }
        if (throwable != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot save changelog acknowledgement for " + session.player().name(), throwable);
            player.sendMessage(messageWithPrefix(
                    session.configuration(), session.configuration().messages().changelogSaveFailed()));
        }
        completeAndClose(player, session);
    }

    private void showChangelogOrFinish(Player player, NoticeSession session, FlowPhase expected) {
        PluginConfiguration configuration = session.configuration();
        boolean shouldShow = FlowPolicy.shouldShowChangelog(
                !session.bypassChangelog() && configuration.changelog().enabled(),
                session.lastSeenRevision(),
                configuration.changelog().revision());
        if (shouldShow) {
            if (!preparePresentation(player, session)) {
                rejectUnsupportedClient(player, session);
                return;
            }
            if (session.transition(expected, FlowPhase.CHANGELOG)) {
                presenter.showChangelog(
                        player, session.player(), configuration, session.token(), session.fallback());
            }
        } else if (session.transition(expected, FlowPhase.COMPLETE)) {
            if (expected != FlowPhase.LOADING) {
                presenter.close(player, session.fallback());
            }
            removeIfCurrent(session);
        }
    }

    private boolean preparePresentation(Player player, NoticeSession session) {
        if (session.presentationPrepared()) {
            return true;
        }
        boolean nativeDialog = presenter.usesNativeDialog(player, session.configuration());
        if (!nativeDialog && !session.configuration().main().clients().fallbackBookEnabled()) {
            return false;
        }
        session.fallback(!nativeDialog);
        return true;
    }

    private void showRulesOrRejectClient(Player player, NoticeSession session) {
        if (!preparePresentation(player, session)) {
            rejectUnsupportedClient(player, session);
            return;
        }
        if (session.transition(FlowPhase.LOADING, FlowPhase.RULES)) {
            presenter.showRules(
                    player, session.player(), session.configuration(), session.token(), false, session.fallback());
        }
    }

    private void rejectUnsupportedClient(Player player, NoticeSession session) {
        session.forcePhase(FlowPhase.COMPLETE);
        removeIfCurrent(session);
        player.kick(plugin.textRenderer().render(session.configuration().messages().unsupportedClient()));
    }

    private void sendLockedMessage(Player player, NoticeSession session) {
        player.sendActionBar(messageWithPrefix(
                session.configuration(), session.configuration().rules().lockedMessage()));
    }

    private Component messageWithPrefix(PluginConfiguration configuration, String message) {
        return plugin.textRenderer().render(configuration.messages().prefix())
                .append(plugin.textRenderer().render(message, Map.of(
                        "server", Component.text(configuration.main().serverId()))));
    }

    private void completeAndClose(Player player, NoticeSession session) {
        session.forcePhase(FlowPhase.COMPLETE);
        presenter.close(player, session.fallback());
        removeIfCurrent(session);
    }

    private boolean isCurrent(NoticeSession session) {
        NoticeSession current = sessions.get(session.player().uniqueId());
        return current != null && current.token().equals(session.token());
    }

    private void removeIfCurrent(NoticeSession session) {
        sessions.computeIfPresent(session.player().uniqueId(), (ignored, current) ->
                current.token().equals(session.token()) ? null : current);
    }
}
