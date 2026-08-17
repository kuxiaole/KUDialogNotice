package dev.kuxiaole.kudialognotice.flow;

import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.storage.PlayerIdentity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class NoticeSession {
    private final UUID token = UUID.randomUUID();
    private final PlayerIdentity player;
    private final PluginConfiguration configuration;
    private final boolean bypassRules;
    private final boolean bypassChangelog;
    private final boolean preview;
    private final AtomicReference<FlowPhase> phase = new AtomicReference<>(FlowPhase.LOADING);
    private final AtomicReference<Boolean> reopenScheduled = new AtomicReference<>(false);
    private final AtomicReference<Boolean> fallback = new AtomicReference<>();
    private volatile long lastSeenRevision;

    NoticeSession(
            PlayerIdentity player,
            PluginConfiguration configuration,
            boolean bypassRules,
            boolean bypassChangelog,
            boolean preview
    ) {
        this.player = player;
        this.configuration = configuration;
        this.bypassRules = bypassRules;
        this.bypassChangelog = bypassChangelog;
        this.preview = preview;
    }

    UUID token() {
        return token;
    }

    PlayerIdentity player() {
        return player;
    }

    PluginConfiguration configuration() {
        return configuration;
    }

    boolean bypassRules() {
        return bypassRules;
    }

    boolean bypassChangelog() {
        return bypassChangelog;
    }

    boolean preview() {
        return preview;
    }

    FlowPhase phase() {
        return phase.get();
    }

    boolean transition(FlowPhase expected, FlowPhase next) {
        return phase.compareAndSet(expected, next);
    }

    void forcePhase(FlowPhase next) {
        phase.set(next);
    }

    long lastSeenRevision() {
        return lastSeenRevision;
    }

    void lastSeenRevision(long revision) {
        lastSeenRevision = revision;
    }

    boolean fallback() {
        return Boolean.TRUE.equals(fallback.get());
    }

    void fallback(boolean fallback) {
        this.fallback.compareAndSet(null, fallback);
    }

    boolean presentationPrepared() {
        return fallback.get() != null;
    }

    boolean markReopenScheduled() {
        return reopenScheduled.compareAndSet(false, true);
    }

    void clearReopenScheduled() {
        reopenScheduled.set(false);
    }
}
