package dev.kuxiaole.kudialognotice.ui;

public enum UserAction {
    RULES_ACCEPT("rules_accept"),
    RULES_REJECT("rules_reject"),
    REJECTION_RETURN("rejection_return"),
    REJECTION_CONFIRM("rejection_confirm"),
    CHANGELOG_ACKNOWLEDGE("changelog_acknowledge");

    private final String commandValue;

    UserAction(String commandValue) {
        this.commandValue = commandValue;
    }

    public String commandValue() {
        return commandValue;
    }

    public static UserAction fromCommandValue(String value) {
        for (UserAction action : values()) {
            if (action.commandValue.equals(value)) {
                return action;
            }
        }
        return null;
    }
}

