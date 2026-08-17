package dev.kuxiaole.kudialognotice.config;

import java.util.List;

public record ChangelogConfig(
        boolean enabled,
        long revision,
        String versionLabel,
        String title,
        List<String> body,
        String acknowledgeButton,
        String acknowledgeTooltip
) {
    public ChangelogConfig {
        body = List.copyOf(body);
    }
}

