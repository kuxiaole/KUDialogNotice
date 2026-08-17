package dev.kuxiaole.kudialognotice.ui;

import dev.kuxiaole.kudialognotice.client.ClientProtocolResolver;
import dev.kuxiaole.kudialognotice.config.ChangelogConfig;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.config.RulesConfig;
import dev.kuxiaole.kudialognotice.storage.PlayerIdentity;
import dev.kuxiaole.kudialognotice.text.TextRenderer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NoticePresenter {
    private static final int DIALOG_BODY_WIDTH = 400;
    private static final int BUTTON_WIDTH = 150;

    private final TextRenderer text;
    private final ClientProtocolResolver protocols;

    public NoticePresenter(TextRenderer text, ClientProtocolResolver protocols) {
        this.text = text;
        this.protocols = protocols;
    }

    public void showRules(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken,
            boolean checkboxInitial,
            boolean fallback
    ) {
        if (!fallback) {
            showNativeRules(player, identity, configuration, sessionToken, checkboxInitial);
        } else {
            showBookRules(player, identity, configuration, sessionToken);
        }
    }

    public void showRejectionConfirmation(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken,
            boolean fallback
    ) {
        if (!fallback) {
            showNativeRejectionConfirmation(player, identity, configuration, sessionToken);
        } else {
            showBookRejectionConfirmation(player, identity, configuration, sessionToken);
        }
    }

    public void showChangelog(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken,
            boolean fallback
    ) {
        if (!fallback) {
            showNativeChangelog(player, identity, configuration, sessionToken);
        } else {
            showBookChangelog(player, identity, configuration, sessionToken);
        }
    }

    public void close(Player player, boolean fallback) {
        if (!fallback) {
            player.closeDialog();
        } else {
            player.closeInventory();
        }
    }

    public boolean usesNativeDialog(Player player, PluginConfiguration configuration) {
        return protocols.protocolVersion(player) >= configuration.main().clients().nativeDialogMinProtocol();
    }

    private void showNativeRules(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken,
            boolean checkboxInitial
    ) {
        RulesConfig rules = configuration.rules();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text.render(rules.title(), placeholders))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(dialogBodies(rules.body(), placeholders))
                        .inputs(List.of(DialogInput.bool(
                                        "rules_read",
                                        text.render(rules.readCheckbox(), placeholders))
                                .initial(checkboxInitial)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        actionButton(rules.acceptButton(), placeholders, UserAction.RULES_ACCEPT, sessionToken),
                        actionButton(rules.rejectButton(), placeholders, UserAction.RULES_REJECT, sessionToken))));
        player.showDialog(dialog);
    }

    private void showNativeRejectionConfirmation(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken
    ) {
        RulesConfig.RejectionConfirmation confirmation = configuration.rules().rejectionConfirmation();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text.render(confirmation.title(), placeholders))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(
                                text.render(confirmation.body(), placeholders), DIALOG_BODY_WIDTH)))
                        .build())
                .type(DialogType.confirmation(
                        actionButton(confirmation.returnButton(), Component.empty(), UserAction.REJECTION_RETURN,
                                placeholders, sessionToken),
                        actionButton(confirmation.rejectButton(), Component.empty(), UserAction.REJECTION_CONFIRM,
                                placeholders, sessionToken))));
        player.showDialog(dialog);
    }

    private void showNativeChangelog(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID sessionToken
    ) {
        ChangelogConfig changelog = configuration.changelog();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text.render(changelog.title(), placeholders))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(dialogBodies(changelog.body(), placeholders))
                        .build())
                .type(DialogType.notice(actionButton(
                        changelog.acknowledgeButton(),
                        text.render(changelog.acknowledgeTooltip(), placeholders),
                        UserAction.CHANGELOG_ACKNOWLEDGE,
                        placeholders,
                        sessionToken))));
        player.showDialog(dialog);
    }

    private void showBookRules(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID token
    ) {
        RulesConfig rules = configuration.rules();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        List<Component> pages = renderPages(rules.body(), placeholders);
        Component actions = text.render(rules.readCheckbox(), placeholders)
                .append(Component.newline())
                .append(text.render(rules.acceptButton().text(), placeholders)
                        .clickEvent(bookAction(token, UserAction.RULES_ACCEPT)))
                .append(Component.text("    "))
                .append(text.render(rules.rejectButton().text(), placeholders)
                        .clickEvent(bookAction(token, UserAction.RULES_REJECT)));
        pages.add(actions);
        player.openBook(Book.book(
                text.render(rules.title(), placeholders),
                Component.text("KUDialogNotice"),
                pages));
    }

    private void showBookRejectionConfirmation(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID token
    ) {
        RulesConfig.RejectionConfirmation confirmation = configuration.rules().rejectionConfirmation();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        Component actions = text.render(confirmation.returnButton(), placeholders)
                .clickEvent(bookAction(token, UserAction.REJECTION_RETURN))
                .append(Component.newline())
                .append(text.render(confirmation.rejectButton(), placeholders)
                        .clickEvent(bookAction(token, UserAction.REJECTION_CONFIRM)));
        player.openBook(Book.book(
                text.render(confirmation.title(), placeholders),
                Component.text("KUDialogNotice"),
                List.of(text.render(confirmation.body(), placeholders), actions)));
    }

    private void showBookChangelog(
            Player player,
            PlayerIdentity identity,
            PluginConfiguration configuration,
            UUID token
    ) {
        ChangelogConfig changelog = configuration.changelog();
        Map<String, Component> placeholders = placeholders(identity, configuration);
        List<Component> pages = renderPages(changelog.body(), placeholders);
        pages.add(text.render(changelog.acknowledgeButton(), placeholders)
                .clickEvent(bookAction(token, UserAction.CHANGELOG_ACKNOWLEDGE)));
        player.openBook(Book.book(
                text.render(changelog.title(), placeholders),
                Component.text("KUDialogNotice"),
                pages));
    }

    private List<DialogBody> dialogBodies(List<String> body, Map<String, Component> placeholders) {
        return body.stream()
                .map(line -> (DialogBody) DialogBody.plainMessage(text.render(line, placeholders), DIALOG_BODY_WIDTH))
                .toList();
    }

    private ActionButton actionButton(
            RulesConfig.Button button,
            Map<String, Component> placeholders,
            UserAction action,
            UUID sessionToken
    ) {
        return actionButton(
                button.text(),
                text.render(button.tooltip(), placeholders),
                action,
                placeholders,
                sessionToken);
    }

    private ActionButton actionButton(
            String label,
            Component tooltip,
            UserAction action,
            Map<String, Component> placeholders,
            UUID sessionToken
    ) {
        return ActionButton.builder(text.render(label, placeholders))
                .tooltip(tooltip)
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(NativeDialogAction.key(action, sessionToken), null))
                .build();
    }

    private List<Component> renderPages(List<String> rawPages, Map<String, Component> placeholders) {
        List<Component> pages = new ArrayList<>(rawPages.size() + 1);
        rawPages.forEach(page -> pages.add(text.render(page, placeholders)));
        return pages;
    }

    private ClickEvent bookAction(UUID token, UserAction action) {
        return ClickEvent.runCommand("/kudialognotice _action " + token + ' ' + action.commandValue());
    }

    private Map<String, Component> placeholders(
            PlayerIdentity identity,
            PluginConfiguration configuration
    ) {
        return Map.of(
                "player", Component.text(identity.name()),
                "server", Component.text(configuration.main().serverId()),
                "version", Component.text(configuration.changelog().versionLabel()),
                "revision", Component.text(configuration.changelog().revision())
        );
    }
}
