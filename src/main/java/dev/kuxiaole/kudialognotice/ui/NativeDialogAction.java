package dev.kuxiaole.kudialognotice.ui;

import net.kyori.adventure.key.Key;

import java.util.Optional;
import java.util.UUID;

public record NativeDialogAction(UUID sessionToken, UserAction action) {
    private static final String NAMESPACE = "kudialognotice";

    public static Key key(UserAction action, UUID sessionToken) {
        return Key.key(NAMESPACE, action.commandValue() + '/' + sessionToken);
    }

    public static Optional<NativeDialogAction> parse(Key key) {
        if (!NAMESPACE.equals(key.namespace())) {
            return Optional.empty();
        }
        int separator = key.value().lastIndexOf('/');
        if (separator <= 0 || separator == key.value().length() - 1) {
            return Optional.empty();
        }
        UserAction action = UserAction.fromCommandValue(key.value().substring(0, separator));
        if (action == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new NativeDialogAction(
                    UUID.fromString(key.value().substring(separator + 1)), action));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
