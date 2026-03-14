package com.yann.autodownload.service;

import com.yann.autodownload.model.WatchConfig;
import it.tdlight.client.Result;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BotGroupService {

    // User account creates/manages the group and topics (bots cannot create groups)
    private final SimpleTelegramClient userClient;
    // Bot account sends DMs to owner and files to the mirror group
    private final SimpleTelegramClient botClient;
    private final ConfigService configService;

    @Value("${user.id}")
    private long ownerUserId;

    public BotGroupService(@Qualifier("userClient") SimpleTelegramClient userClient,
                           @Qualifier("botClient") SimpleTelegramClient botClient,
                           ConfigService configService) {
        this.userClient = userClient;
        this.botClient = botClient;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        WatchConfig config = configService.getConfig();
        if (config.getMirrorGroupId() == 0) {
            log.info("No mirror group configured. Use /setup command to create one.");
        } else {
            log.info("Mirror group already configured: {}", config.getMirrorGroupId());
        }
    }

    /**
     * Creates the mirror forum supergroup using the USER account (bots cannot create groups),
     * then adds the bot as an admin so the bot can send files to it.
     */
    public void createMirrorGroup() {
        try {
            // Step 1: User account creates ONE supergroup (megagroup, not channel).
            // Each watched group gets a topic inside this single group.
            TdApi.CreateNewSupergroupChat createReq = new TdApi.CreateNewSupergroupChat();
            createReq.title = "Auto Mirror";
            createReq.isChannel = false;   // supergroup (megagroup), not a broadcast channel
            createReq.isForum = false;     // will be toggled after creation
            createReq.description = "One group for all watched sources. Each source = one topic.";

            TdApi.Chat chat = sendSync(userClient, createReq);
            long mirrorGroupId = chat.id;
            log.info("Created mirror supergroup with id: {}", mirrorGroupId);

            // Step 2: Enable forum mode (topics). Per Telegram docs, supergroups must be
            // converted to forums after creation via ToggleSupergroupIsForum.
            long supergroupId = chatIdToSupergroupId(mirrorGroupId);
            TdApi.ToggleSupergroupIsForum toggleForum = new TdApi.ToggleSupergroupIsForum();
            toggleForum.supergroupId = supergroupId;
            toggleForum.isForum = true;
            sendSync(userClient, toggleForum);
            log.info("Forum mode enabled for supergroup {}", supergroupId);

            // Step 2: Get the bot's user ID
            long botUserId = getBotUserId();
            if (botUserId > 0) {
                // Step 3: Add bot as member
                try {
                    TdApi.AddChatMember addMember = new TdApi.AddChatMember();
                    addMember.chatId = mirrorGroupId;
                    addMember.userId = botUserId;
                    addMember.forwardLimit = 0;
                    sendSync(userClient, addMember);
                    log.info("Added bot (userId={}) to mirror group", botUserId);

                    // Step 4: Promote bot to admin with send rights
                    promoteBotToAdmin(mirrorGroupId, botUserId);
                } catch (Exception e) {
                    log.warn("Could not add bot to mirror group (bot will use user account for sending): {}",
                            e.getMessage());
                }
            }

            WatchConfig config = configService.getConfig();
            config.setMirrorGroupId(mirrorGroupId);
            configService.saveConfig();

            sendMessageToOwner("Mirror group created!\nGroup ID: " + mirrorGroupId
                    + "\nBot added as admin: " + (botUserId > 0));
        } catch (Exception e) {
            log.error("Failed to create mirror group: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create mirror group", e);
        }
    }

    /**
     * Creates a forum topic in the mirror group using the USER account.
     * Returns the messageThreadId (topicId).
     */
    public long createTopic(String topicName) {
        WatchConfig config = configService.getConfig();
        long mirrorGroupId = config.getMirrorGroupId();

        if (mirrorGroupId == 0) {
            throw new IllegalStateException("Mirror group not set up yet. Use /setup first.");
        }

        try {
            TdApi.CreateForumTopic topicReq = new TdApi.CreateForumTopic();
            topicReq.chatId = mirrorGroupId;
            topicReq.name = topicName;
            topicReq.icon = new TdApi.ForumTopicIcon();
            topicReq.icon.color = 0x6FB9F0;
            topicReq.icon.customEmojiId = 0L;

            TdApi.ForumTopicInfo topicInfo = sendSync(userClient, topicReq);
            long topicId = topicInfo.messageThreadId;
            log.info("Created forum topic '{}' with id: {}", topicName, topicId);
            return topicId;
        } catch (Exception e) {
            log.error("Failed to create forum topic '{}': {}", topicName, e.getMessage(), e);
            throw new RuntimeException("Failed to create topic: " + topicName, e);
        }
    }

    /**
     * Deletes a forum topic using the USER account (group owner/admin).
     */
    public void deleteTopic(long topicId) {
        WatchConfig config = configService.getConfig();
        long mirrorGroupId = config.getMirrorGroupId();

        if (mirrorGroupId == 0) {
            log.warn("Cannot delete topic - mirror group not configured");
            return;
        }

        try {
            TdApi.DeleteForumTopic deleteReq = new TdApi.DeleteForumTopic();
            deleteReq.chatId = mirrorGroupId;
            deleteReq.messageThreadId = topicId;
            sendSync(userClient, deleteReq);
            log.info("Deleted forum topic: {}", topicId);
        } catch (Exception e) {
            log.warn("Failed to delete forum topic {}: {}", topicId, e.getMessage());
        }
    }

    /**
     * Returns the current mirror group ID from config.
     */
    public long getMirrorGroupId() {
        return configService.getConfig().getMirrorGroupId();
    }

    /**
     * Converts a supergroup chat ID (e.g. -1001234567890) to a supergroup ID (1234567890).
     * Formula: supergroupId = -(chatId) - 1_000_000_000_000L
     */
    private static long chatIdToSupergroupId(long chatId) {
        return -chatId - 1_000_000_000_000L;
    }

    /**
     * Gets the bot's Telegram user ID via the bot client.
     */
    private long getBotUserId() {
        try {
            TdApi.User me = sendSync(botClient, new TdApi.GetMe());
            return me.id;
        } catch (Exception e) {
            log.error("Failed to get bot user ID: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Promotes the bot to admin in the mirror group so it can send messages.
     */
    private void promoteBotToAdmin(long mirrorGroupId, long botUserId) {
        try {
            TdApi.ChatAdministratorRights rights = new TdApi.ChatAdministratorRights();
            rights.canManageChat = true;
            rights.canPostMessages = true;
            rights.canEditMessages = true;
            rights.canDeleteMessages = true;
            rights.canInviteUsers = true;
            rights.canManageTopics = true;

            TdApi.ChatMemberStatusAdministrator adminStatus = new TdApi.ChatMemberStatusAdministrator();
            adminStatus.rights = rights;
            adminStatus.canBeEdited = true;
            adminStatus.customTitle = "AutoDownload";

            TdApi.SetChatMemberStatus setStatus = new TdApi.SetChatMemberStatus();
            setStatus.chatId = mirrorGroupId;
            setStatus.memberId = new TdApi.MessageSenderUser(botUserId);
            setStatus.status = adminStatus;

            sendSync(userClient, setStatus);
            log.info("Promoted bot to admin in mirror group");
        } catch (Exception e) {
            log.warn("Failed to promote bot to admin: {}", e.getMessage());
        }
    }

    /**
     * Sends a text message to the owner via the bot account DM.
     */
    public void sendMessageToOwner(String text) {
        try {
            TdApi.InputMessageText textContent = new TdApi.InputMessageText();
            textContent.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
            TdApi.SendMessage req = new TdApi.SendMessage();
            req.chatId = ownerUserId;
            req.inputMessageContent = textContent;
            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to send DM to owner: {}", result.getError().message);
                }
            });
        } catch (Exception e) {
            log.error("Exception sending DM to owner", e);
        }
    }

    /**
     * Sends a TDLib request synchronously with a 30s timeout.
     */
    public static <R extends TdApi.Object> R sendSync(SimpleTelegramClient client,
                                                       TdApi.Function<R> req) throws Exception {
        CompletableFuture<R> future = client.send(req);
        return future.get(30, TimeUnit.SECONDS);
    }
}
