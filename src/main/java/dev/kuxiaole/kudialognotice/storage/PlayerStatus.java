package dev.kuxiaole.kudialognotice.storage;

public record PlayerStatus(PlayerIdentity player, boolean rulesAccepted, long lastSeenRevision) {
}

