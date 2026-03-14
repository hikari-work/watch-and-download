package com.yann.autodownload.service;

import com.yann.autodownload.event.BotUpdateEvent;
import com.yann.autodownload.model.ContentType;
import com.yann.autodownload.model.WatchConfig;
import com.yann.autodownload.model.WatchEntry;
import com.yann.autodownload.util.HtmlUtil;
import it.tdlight.client.Result;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BotCommandService {

    // Content types shown in the toggle keyboard (ordered for layout)
    private static final ContentType[] TOGGLEABLE_TYPES = {
            ContentType.PHOTO,      ContentType.VIDEO,
            ContentType.DOCUMENT,   ContentType.AUDIO,
            ContentType.ANIMATION,  ContentType.VOICE,
            ContentType.VIDEO_NOTE, ContentType.STICKER
    };

    private final SimpleTelegramClient botClient;
    private final ConfigService configService;
    private final BotGroupService botGroupService;
    private final WatchService watchService;
    private final BackupService backupService;

    private ExecutorService executor;

    public BotCommandService(@Qualifier("botClient") SimpleTelegramClient botClient,
                             ConfigService configService,
                             BotGroupService botGroupService,
                             WatchService watchService,
                             BackupService backupService) {
        this.botClient = botClient;
        this.configService = configService;
        this.botGroupService = botGroupService;
        this.watchService = watchService;
        this.backupService = backupService;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bot-cmd-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("BotCommandService initialized");
    }

    @EventListener
    public void onBotUpdate(BotUpdateEvent event) {
        if (event.update() instanceof TdApi.UpdateNewMessage updateMsg) {
            TdApi.Message message = updateMsg.message;
            if (message.content instanceof TdApi.MessageText textMsg) {
                String text = textMsg.text.text.trim();
                if (text.startsWith("/")) {
                    executor.submit(() -> {
                        try {
                            handleCommand(message, text);
                        } catch (Exception e) {
                            log.error("Error handling command '{}': {}", text, e.getMessage(), e);
                            sendHtml(message.chatId,
                                    "❌ <b>Error</b>\n<code>" + esc(e.getMessage()) + "</code>");
                        }
                    });
                }
            }
        } else if (event.update() instanceof TdApi.UpdateNewCallbackQuery callbackQuery) {
            executor.submit(() -> {
                try {
                    handleCallbackQuery(callbackQuery);
                } catch (Exception e) {
                    log.error("Error handling callback query: {}", e.getMessage(), e);
                }
            });
        }
    }

    private void handleCommand(TdApi.Message message, String text) {
        long chatId = message.chatId;
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        if (command.contains("@")) {
            command = command.substring(0, command.indexOf('@'));
        }
        String args = parts.length > 1 ? parts[1].trim() : "";

        log.info("Received command '{}' with args '{}' from chatId={}", command, args, chatId);

        switch (command) {
            case "/start", "/help" -> handleStart(chatId);
            case "/setup"          -> handleSetup(chatId);
            case "/add_group"      -> handleAddGroup(chatId, args);
            case "/add_content"    -> handleAddContent(chatId, args);
            case "/list"           -> handleList(chatId);
            case "/delete"         -> handleDelete(chatId, args);
            case "/backup"         -> handleBackup(chatId);
            default -> sendHtml(chatId, "❓ Unknown command. Use /help to see available commands.");
        }
    }

    /**
     * Handles inline keyboard callback queries.
     * Data format:
     *   toggle:{groupId}:{TYPE}  — toggles one content type on/off
     *   done:{groupId}           — closes the keyboard and shows summary
     */
    private void handleCallbackQuery(TdApi.UpdateNewCallbackQuery query) {
        String data = null;
        if (query.payload instanceof TdApi.CallbackQueryPayloadData payloadData) {
            data = new String(payloadData.data);
        }
        if (data == null || data.isEmpty()) {
            answerCallbackQuery(query.id, "");
            return;
        }

        long chatId    = query.chatId;
        long messageId = query.messageId;

        if (data.startsWith("toggle:")) {
            String[] parts = data.split(":", 3);
            if (parts.length != 3) { answerCallbackQuery(query.id, "Invalid data"); return; }

            long groupId = Long.parseLong(parts[1]);
            ContentType type = ContentType.valueOf(parts[2]);

            WatchConfig config = configService.getConfig();
            WatchEntry entry = findEntry(config, groupId);
            if (entry == null) { answerCallbackQuery(query.id, "Group not found"); return; }

            if (entry.getContentTypes() == null) entry.setContentTypes(new HashSet<>());
            boolean wasEnabled = entry.getContentTypes().contains(type);
            if (wasEnabled) entry.getContentTypes().remove(type);
            else            entry.getContentTypes().add(type);
            configService.saveConfig();

            String typeName = capitalize(type.name());
            answerCallbackQuery(query.id, typeName + (wasEnabled ? " disabled" : " enabled"));
            editToggleKeyboard(chatId, messageId, groupId, entry);

        } else if (data.startsWith("done:")) {
            long groupId = Long.parseLong(data.substring("done:".length()));
            WatchConfig config = configService.getConfig();
            WatchEntry entry = findEntry(config, groupId);

            answerCallbackQuery(query.id, "✅ Saved!");

            if (entry == null) {
                editMessageText(chatId, messageId, "Group not found.");
            } else {
                String types = (entry.getContentTypes() == null || entry.getContentTypes().isEmpty())
                        ? "<i>(none)</i>"
                        : entry.getContentTypes().stream()
                            .map(t -> "<code>" + t.name().toLowerCase() + "</code>")
                            .sorted()
                            .collect(Collectors.joining(", "));
                editMessageHtml(chatId, messageId,
                        "✅ <b>Saved!</b>\n"
                        + "📌 <b>" + esc(entry.getSourceGroupName()) + "</b>\n"
                        + "Watching: " + types);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Command handlers
    // ──────────────────────────────────────────────────────────────────────────

    private void handleStart(long chatId) {
        sendHtml(chatId, """
                🤖 <b>AutoDownload Bot</b>

                <b>Setup</b>
                /setup — Create mirror forum group

                <b>Watchlist</b>
                /add_group <code>&lt;link_or_id&gt;</code> — Add a group
                /add_content <code>&lt;group_id&gt;</code> — Toggle content types
                /list — Show all watched groups
                /delete <code>&lt;group_id&gt;</code> — Remove a group

                <b>Other</b>
                /backup — Send manual config backup

                <i>Supported link formats:</i>
                <code>https://t.me/c/1234567890/1</code>
                <code>https://t.me/username</code>
                <code>-1001234567890</code>""");
    }

    private void handleSetup(long chatId) {
        WatchConfig config = configService.getConfig();
        if (config.getMirrorGroupId() == 0) {
            sendHtml(chatId, "⏳ Creating mirror forum group…");
            try {
                botGroupService.createMirrorGroup();
                long mirrorGroupId = botGroupService.getMirrorGroupId();
                sendHtml(chatId, "✅ <b>Mirror group created!</b>\n"
                        + "ID: <code>" + mirrorGroupId + "</code>");
            } catch (Exception e) {
                sendHtml(chatId, "❌ <b>Failed to create mirror group</b>\n"
                        + "<code>" + esc(e.getMessage()) + "</code>");
            }
        } else {
            sendHtml(chatId, "ℹ️ <b>Mirror group already set up</b>\n"
                    + "ID: <code>" + config.getMirrorGroupId() + "</code>");
        }
    }

    private void handleAddGroup(long chatId, String args) {
        if (args.isEmpty()) {
            sendHtml(chatId, """
                    ℹ️ <b>Usage:</b> /add_group <code>&lt;link_or_id&gt;</code>

                    <b>Examples:</b>
                    <code>https://t.me/c/1234567890/1</code>
                    <code>https://t.me/username</code>
                    <code>-1001234567890</code>""");
            return;
        }

        WatchConfig config = configService.getConfig();
        if (config.getMirrorGroupId() == 0) {
            sendHtml(chatId, "⚠️ Mirror group not set up. Run /setup first.");
            return;
        }

        long sourceGroupId;
        try {
            sourceGroupId = parseGroupId(args);
        } catch (Exception e) {
            sendHtml(chatId, "❌ <b>Failed to parse group ID</b>\n"
                    + "<code>" + esc(e.getMessage()) + "</code>");
            return;
        }

        boolean alreadyWatched = config.getEntries().stream()
                .anyMatch(e -> e.getSourceGroupId() == sourceGroupId);
        if (alreadyWatched) {
            sendHtml(chatId, "⚠️ Group <code>" + sourceGroupId + "</code> is already in the watchlist.");
            return;
        }

        String groupName;
        try {
            groupName = watchService.getGroupName(sourceGroupId);
        } catch (Exception e) {
            groupName = "Group " + sourceGroupId;
            log.warn("Could not get name for group {}: {}", sourceGroupId, e.getMessage());
        }

        watchService.joinGroup(sourceGroupId);

        long topicId;
        try {
            topicId = botGroupService.createTopic(groupName);
        } catch (Exception e) {
            sendHtml(chatId, "❌ <b>Failed to create forum topic</b> for <i>" + esc(groupName) + "</i>\n"
                    + "<code>" + esc(e.getMessage()) + "</code>");
            return;
        }

        WatchEntry entry = new WatchEntry();
        entry.setSourceGroupId(sourceGroupId);
        entry.setSourceGroupName(groupName);
        entry.setTopicId(topicId);
        entry.setContentTypes(new HashSet<>());
        config.getEntries().add(entry);
        configService.saveConfig();

        sendToggleKeyboard(chatId, sourceGroupId, entry,
                "➕ <b>" + esc(groupName) + "</b> added!\n"
                + "Now select content types to watch:");
    }

    private void handleAddContent(long chatId, String args) {
        if (args.isEmpty()) {
            WatchConfig config = configService.getConfig();
            if (config.getEntries() == null || config.getEntries().isEmpty()) {
                sendHtml(chatId, "⚠️ No groups in watchlist. Use /add_group first.");
                return;
            }
            StringBuilder sb = new StringBuilder("📋 <b>Select a group:</b>\n\n");
            for (WatchEntry e : config.getEntries()) {
                sb.append("• <b>").append(esc(e.getSourceGroupName())).append("</b>\n")
                  .append("  <code>/add_content ").append(e.getSourceGroupId()).append("</code>\n");
            }
            sendHtml(chatId, sb.toString());
            return;
        }

        long groupId;
        try {
            groupId = Long.parseLong(args.trim());
        } catch (NumberFormatException e) {
            sendHtml(chatId, "❌ Invalid group ID: <code>" + esc(args) + "</code>");
            return;
        }

        WatchConfig config = configService.getConfig();
        WatchEntry entry = findEntry(config, groupId);
        if (entry == null) {
            sendHtml(chatId, "❌ Group <code>" + groupId + "</code> not found in watchlist.");
            return;
        }

        sendToggleKeyboard(chatId, groupId, entry,
                "🔧 <b>" + esc(entry.getSourceGroupName()) + "</b>\nToggle content types:");
    }

    private void handleList(long chatId) {
        WatchConfig config = configService.getConfig();
        if (config.getEntries() == null || config.getEntries().isEmpty()) {
            sendHtml(chatId, "📭 No groups in watchlist. Use /add_group to add one.");
            return;
        }

        StringBuilder sb = new StringBuilder("📋 <b>Watched groups</b>\n\n");
        for (WatchEntry entry : config.getEntries()) {
            String types = (entry.getContentTypes() == null || entry.getContentTypes().isEmpty())
                    ? "<i>(none)</i>"
                    : entry.getContentTypes().stream()
                        .map(t -> "<code>" + t.name().toLowerCase() + "</code>")
                        .sorted()
                        .collect(Collectors.joining(", "));
            sb.append("📌 <b>").append(esc(entry.getSourceGroupName())).append("</b>\n")
              .append("ID: <code>").append(entry.getSourceGroupId()).append("</code>\n")
              .append("Types: ").append(types).append("\n\n");
        }
        sb.append("🪞 Mirror group: <code>").append(config.getMirrorGroupId()).append("</code>");
        sendHtml(chatId, sb.toString());
    }

    private void handleDelete(long chatId, String args) {
        if (args.isEmpty()) {
            sendHtml(chatId, "ℹ️ <b>Usage:</b> /delete <code>&lt;group_id&gt;</code>\n"
                    + "Example: <code>/delete -1001234567890</code>");
            return;
        }

        long groupId;
        try {
            groupId = Long.parseLong(args.trim());
        } catch (NumberFormatException e) {
            sendHtml(chatId, "❌ Invalid group ID: <code>" + esc(args) + "</code>");
            return;
        }

        WatchConfig config = configService.getConfig();
        WatchEntry entry = findEntry(config, groupId);
        if (entry == null) {
            sendHtml(chatId, "❌ Group <code>" + groupId + "</code> not found in watchlist.");
            return;
        }

        if (entry.getTopicId() != 0) {
            try {
                botGroupService.deleteTopic(entry.getTopicId());
            } catch (Exception e) {
                log.warn("Failed to delete forum topic {} for group {}: {}",
                        entry.getTopicId(), groupId, e.getMessage());
            }
        }

        String groupName = entry.getSourceGroupName();
        config.getEntries().remove(entry);
        configService.saveConfig();

        sendHtml(chatId, "🗑️ Removed <b>" + esc(groupName) + "</b>\n"
                + "ID: <code>" + groupId + "</code>");
    }

    private void handleBackup(long chatId) {
        try {
            backupService.sendBackup();
            sendHtml(chatId, "✅ <b>Backup sent!</b>");
        } catch (Exception e) {
            sendHtml(chatId, "❌ <b>Backup failed</b>\n<code>" + esc(e.getMessage()) + "</code>");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Toggle keyboard helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void sendToggleKeyboard(long chatId, long groupId, WatchEntry entry, String htmlCaption) {
        try {
            TdApi.InputMessageText textContent = new TdApi.InputMessageText();
            textContent.text = parseHtml(htmlCaption);

            TdApi.SendMessage req = new TdApi.SendMessage();
            req.chatId              = chatId;
            req.inputMessageContent = textContent;
            req.replyMarkup         = buildToggleKeyboard(groupId, entry.getContentTypes());

            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to send toggle keyboard: {}", result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception sending toggle keyboard", e);
        }
    }

    private void editToggleKeyboard(long chatId, long messageId, long groupId, WatchEntry entry) {
        try {
            TdApi.EditMessageReplyMarkup req = new TdApi.EditMessageReplyMarkup();
            req.chatId      = chatId;
            req.messageId   = messageId;
            req.replyMarkup = buildToggleKeyboard(groupId, entry.getContentTypes());

            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to edit toggle keyboard: {}", result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception editing toggle keyboard", e);
        }
    }

    /**
     * Builds the inline keyboard with one toggle button per content type (2 per row)
     * plus a "✅ Done" button at the end.
     */
    private TdApi.ReplyMarkupInlineKeyboard buildToggleKeyboard(long groupId, Set<ContentType> enabled) {
        Set<ContentType> active = (enabled != null) ? enabled : Set.of();
        List<TdApi.InlineKeyboardButton[]> rows = new ArrayList<>();

        // TOGGLEABLE_TYPES always has an even count — pair up 2 per row
        for (int i = 0; i < TOGGLEABLE_TYPES.length; i += 2) {
            rows.add(new TdApi.InlineKeyboardButton[]{
                    toggleButton(groupId, TOGGLEABLE_TYPES[i],     active.contains(TOGGLEABLE_TYPES[i])),
                    toggleButton(groupId, TOGGLEABLE_TYPES[i + 1], active.contains(TOGGLEABLE_TYPES[i + 1]))
            });
        }

        TdApi.InlineKeyboardButton doneBtn = new TdApi.InlineKeyboardButton();
        doneBtn.text = "✅ Done";
        TdApi.InlineKeyboardButtonTypeCallback doneType = new TdApi.InlineKeyboardButtonTypeCallback();
        doneType.data = ("done:" + groupId).getBytes();
        doneBtn.type  = doneType;
        rows.add(new TdApi.InlineKeyboardButton[]{doneBtn});

        TdApi.ReplyMarkupInlineKeyboard keyboard = new TdApi.ReplyMarkupInlineKeyboard();
        keyboard.rows = rows.toArray(new TdApi.InlineKeyboardButton[0][]);
        return keyboard;
    }

    private TdApi.InlineKeyboardButton toggleButton(long groupId, ContentType type, boolean enabled) {
        TdApi.InlineKeyboardButton btn = new TdApi.InlineKeyboardButton();
        btn.text = (enabled ? "✅ " : "☐ ") + capitalize(type.name());
        TdApi.InlineKeyboardButtonTypeCallback cbType = new TdApi.InlineKeyboardButtonTypeCallback();
        cbType.data = ("toggle:" + groupId + ":" + type.name()).getBytes();
        btn.type = cbType;
        return btn;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Message sending / editing
    // ──────────────────────────────────────────────────────────────────────────

    /** Sends an HTML-formatted message. */
    public void sendHtml(long chatId, String html) {
        try {
            TdApi.InputMessageText textContent = new TdApi.InputMessageText();
            textContent.text = parseHtml(html);
            TdApi.SendMessage req = new TdApi.SendMessage();
            req.chatId              = chatId;
            req.inputMessageContent = textContent;
            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to send message to chatId={}: {}", chatId, result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception sending message to chatId={}", chatId, e);
        }
    }

    private void editMessageText(long chatId, long messageId, String plainText) {
        editMessageHtml(chatId, messageId, esc(plainText));
    }

    private void editMessageHtml(long chatId, long messageId, String html) {
        try {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = parseHtml(html);

            TdApi.EditMessageText req = new TdApi.EditMessageText();
            req.chatId              = chatId;
            req.messageId           = messageId;
            req.inputMessageContent = content;

            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to edit message: {}", result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception editing message", e);
        }
    }

    private void answerCallbackQuery(long queryId, String text) {
        try {
            TdApi.AnswerCallbackQuery req = new TdApi.AnswerCallbackQuery();
            req.callbackQueryId = queryId;
            req.text            = text;
            req.showAlert       = false;
            botClient.send(req, (Result<TdApi.Ok> result) -> {
                if (result.isError()) {
                    log.warn("Failed to answer callback query: {}", result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception answering callback query", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTML / text utilities
    // ──────────────────────────────────────────────────────────────────────────

    private static TdApi.FormattedText parseHtml(String html) {
        return HtmlUtil.parse(html);
    }

    private static String esc(String text) {
        return HtmlUtil.esc(text);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String lower = s.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ──────────────────────────────────────────────────────────────────────────

    private WatchEntry findEntry(WatchConfig config, long groupId) {
        if (config.getEntries() == null) return null;
        return config.getEntries().stream()
                .filter(e -> e.getSourceGroupId() == groupId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Parses a group ID from various formats:
     *  https://t.me/c/{id}/{msgid}  →  -(1000000000000 + id)
     *  https://t.me/{username}      →  resolve via TDLib
     *  negative number              →  use directly
     */
    private long parseGroupId(String input) {
        input = input.trim();

        if (input.startsWith("https://t.me/c/")) {
            String path  = input.substring("https://t.me/c/".length());
            String idStr = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            return -(1000000000000L + Long.parseLong(idStr.trim()));
        }

        if (input.startsWith("https://t.me/")) {
            String username = input.substring("https://t.me/".length());
            if (username.contains("/")) username = username.substring(0, username.indexOf('/'));
            return watchService.resolveUsername(username);
        }

        try {
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse group ID from: " + input);
        }
    }
}
