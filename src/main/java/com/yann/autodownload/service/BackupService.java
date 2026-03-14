package com.yann.autodownload.service;

import it.tdlight.client.Result;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class BackupService {

    private final SimpleTelegramClient botClient;
    private final ConfigService configService;

    @Value("${user.id}")
    private long ownerUserId;

    public BackupService(@Qualifier("botClient") SimpleTelegramClient botClient,
                         ConfigService configService) {
        this.botClient = botClient;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        log.info("BackupService initialized. First backup will run after 1 minute.");
        Thread initialBackupThread = new Thread(() -> {
            try {
                Thread.sleep(60_000);
                log.info("Running initial backup after startup delay");
                sendBackup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Initial backup thread interrupted");
            } catch (Exception e) {
                log.error("Error during initial backup", e);
            }
        }, "initial-backup");
        initialBackupThread.setDaemon(true);
        initialBackupThread.start();
    }

    @Scheduled(fixedDelay = 3600_000)
    public void sendBackup() {
        log.info("Sending config backup to owner (userId={})", ownerUserId);
        configService.saveConfig();

        String configFilePath = configService.getConfigFilePath();
        String captionText = "Hourly config backup - " + LocalDateTime.now();

        try {
            TdApi.InputMessageDocument docContent = new TdApi.InputMessageDocument();
            docContent.document = new TdApi.InputFileLocal(configFilePath);
            docContent.caption = new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);

            TdApi.SendMessage req = new TdApi.SendMessage();
            req.chatId = ownerUserId;
            req.inputMessageContent = docContent;

            botClient.send(req, (Result<TdApi.Message> result) -> {
                if (result.isError()) {
                    log.error("Failed to send backup to owner: {}", result.getError().message);
                } else {
                    log.info("Config backup sent to owner successfully");
                }
            });
        } catch (Exception e) {
            log.error("Exception while sending config backup", e);
        }
    }
}
