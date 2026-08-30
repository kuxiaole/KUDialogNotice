package dev.kuxiaole.kudialognotice.config;

public record PluginConfiguration(
        MainConfig main,
        RulesConfig rules,
        ChangelogConfig changelog,
        MessagesConfig messages,
        ChangelogDocument changelogDocument
) {
    /**
     * Source-compatible constructor for integrations that build a configuration
     * from the parsed changelog only. File loading uses the five-argument
     * constructor so the original YAML payload (including comments) is kept.
     */
    public PluginConfiguration(
            MainConfig main,
            RulesConfig rules,
            ChangelogConfig changelog,
            MessagesConfig messages
    ) {
        this(main, rules, changelog, messages,
                new ChangelogDocument(changelog, ChangelogCodec.serialize(changelog)));
    }

    public PluginConfiguration {
        if (changelogDocument == null) {
            throw new IllegalArgumentException("changelogDocument cannot be null");
        }
        if (!changelog.equals(changelogDocument.configuration())) {
            throw new IllegalArgumentException("changelogDocument does not match changelog");
        }
    }

    /** Return a copy with a new parsed and serialized changelog document. */
    public PluginConfiguration withChangelog(ChangelogDocument document) {
        return new PluginConfiguration(main, rules, document.configuration(), messages, document);
    }
}
