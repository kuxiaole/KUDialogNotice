package dev.kuxiaole.kudialognotice.config;

import java.util.List;

public record RulesConfig(
        boolean enabled,
        String title,
        List<String> body,
        String readCheckbox,
        String lockedMessage,
        Button acceptButton,
        Button rejectButton,
        RejectionConfirmation rejectionConfirmation
) {
    public RulesConfig {
        body = List.copyOf(body);
    }

    public record Button(String text, String tooltip) {
    }

    public record RejectionConfirmation(
            String title,
            String body,
            String returnButton,
            String rejectButton
    ) {
    }
}

