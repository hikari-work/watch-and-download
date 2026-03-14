package com.yann.autodownload.service;

import com.yann.autodownload.event.UserUpdateEvent;
import com.yann.autodownload.model.ContentType;
import com.yann.autodownload.model.WatchConfig;
import com.yann.autodownload.model.WatchEntry;
import it.tdlight.client.Result;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class WatchService {

    private final SimpleTelegramClient userClient;
    private final ConfigService configService;
    private final DownloadService downloadService;

    private ExecutorService executor;

    public WatchService(@Qualifier("userClient") SimpleTelegramClient userClient,
                        ConfigService configService,
                        DownloadService downloadService) {
        this.userClient = userClient;
        this.configService = configService;
        this.downloadService = downloadService;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "watch-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("WatchService initialized");
    }

    @EventListener
    public void onUserUpdate(UserUpdateEvent event) {
        // UpdateFile events must be handled synchronously (no executor submit) so that
        // CompletableFutures in DownloadService are completed promptly without thread starvation.
        if (event.update() instanceof TdApi.UpdateFile fileUpdate) {
            downloadService.onFileUpdate(fileUpdate);
            return;
        }
        if (event.update() instanceof TdApi.UpdateNewMessage updateMsg) {
            executor.submit(() -> {
                try {
                    processMessage(updateMsg.message);
                } catch (Exception e) {
                    log.error("Error processing message in watch service", e);
                }
            });
        }
    }

    private void processMessage(TdApi.Message message) {
        WatchConfig config = configService.getConfig();
        if (config.getEntries() == null || config.getEntries().isEmpty()) {
            return;
        }

        WatchEntry matchedEntry = null;
        for (WatchEntry entry : config.getEntries()) {
            if (entry.getSourceGroupId() == message.chatId) {
                matchedEntry = entry;
                break;
            }
        }

        if (matchedEntry == null) {
            return;
        }

        ContentType contentType = ContentType.fromMessageContent(message.content);
        if (contentType == null) {
            log.debug("Unrecognized content type in message from chat {}", message.chatId);
            return;
        }

        if (matchedEntry.getContentTypes() == null
                || !matchedEntry.getContentTypes().contains(contentType)) {
            log.debug("Content type {} not watched for group {}", contentType, message.chatId);
            return;
        }

        log.info("Matched message from group '{}' (id={}) with content type {}",
                matchedEntry.getSourceGroupName(), message.chatId, contentType);

        long mirrorGroupId = config.getMirrorGroupId();
        if (mirrorGroupId == 0) {
            log.warn("Mirror group not configured, cannot forward message from group {}",
                    message.chatId);
            return;
        }

        downloadService.processAndForward(message, matchedEntry, mirrorGroupId);
    }

    /**
     * Makes the user client join a group/channel by chatId.
     */
    public void joinGroup(long chatId) {
        TdApi.JoinChat req = new TdApi.JoinChat();
        req.chatId = chatId;
        userClient.send(req, (Result<TdApi.Ok> result) -> {
            if (result.isError()) {
                log.error("Failed to join chat {}: {}", chatId, result.getError().message);
            } else {
                log.info("Successfully joined chat {}", chatId);
            }
        });
    }

    /**
     * Gets the group/channel name via the user client.
     */
    public String getGroupName(long chatId) {
        try {
            TdApi.GetChat req = new TdApi.GetChat();
            req.chatId = chatId;
            TdApi.Chat chat = userClient.send(req).get(30, TimeUnit.SECONDS);
            return chat.title;
        } catch (Exception e) {
            log.error("Failed to get chat info for chatId {}: {}", chatId, e.getMessage());
            return "Unknown Group";
        }
    }

    /**
     * Resolves a public username to a chatId via user client.
     */
    public long resolveUsername(String username) {
        try {
            TdApi.SearchPublicChat req = new TdApi.SearchPublicChat();
            req.username = username;
            TdApi.Chat chat = userClient.send(req).get(30, TimeUnit.SECONDS);
            return chat.id;
        } catch (Exception e) {
            log.error("Failed to resolve username '{}': {}", username, e.getMessage());
            throw new RuntimeException("Could not resolve username: " + username, e);
        }
    }
}
