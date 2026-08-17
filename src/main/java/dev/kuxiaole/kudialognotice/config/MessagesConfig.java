package dev.kuxiaole.kudialognotice.config;

public record MessagesConfig(
        String prefix,
        String databaseUnavailable,
        String unsupportedClient,
        String rulesSaveFailed,
        String changelogSaveFailed,
        String rejectedKick,
        String reloadSuccess,
        String reloadFailed,
        String noPermission,
        String playerNotFound,
        String invalidCommand
) {
}
