package dev.kuxiaole.kudialognotice.config;

public record PluginConfiguration(
        MainConfig main,
        RulesConfig rules,
        ChangelogConfig changelog,
        MessagesConfig messages
) {
}

