package com.yann.autodownload.configuration;

import com.yann.autodownload.event.BotUpdateEvent;
import com.yann.autodownload.event.UserUpdateEvent;
import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Configuration
public class BotConfiguration {

    @Value("${bot.token}")
    private String botToken;

    @Value("${phone.number}")
    private String phoneNumber;

    @Value("${api.hash}")
    private String apiHash;

    @Value("${api.id}")
    private Integer apiId;

    // Each client requires its own factory instance
    private SimpleTelegramClientFactory userFactory;
    private SimpleTelegramClientFactory botFactory;

    private static volatile boolean tdlightInitialized = false;

    private static synchronized void ensureTdLightInit() {
        if (!tdlightInitialized) {
            try {
                Init.init();
            } catch (Throwable t) {
                throw new RuntimeException("Failed to initialize TDLight native library", t);
            }
            Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());
            tdlightInitialized = true;
            log.info("TDLight native library initialized");
        }
    }

    @Bean("userClient")
    public SimpleTelegramClient userClient(ApplicationEventPublisher eventPublisher) throws Exception {
        ensureTdLightInit();

        APIToken apiToken = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(apiToken);

        Path sessionPath = Paths.get("sessions/user");
        settings.setDatabaseDirectoryPath(sessionPath);
        settings.setDownloadedFilesDirectoryPath(Paths.get("sessions/shared-downloads"));

        userFactory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder builder = userFactory.builder(settings);

        builder.addUpdatesHandler(update -> {
            try {
                eventPublisher.publishEvent(new UserUpdateEvent(update));
            } catch (Exception e) {
                log.error("Error publishing UserUpdateEvent: {}", e.getMessage());
            }
        });

        log.info("Building user client (phone auth)");
        return builder.build(AuthenticationSupplier.user(phoneNumber));
    }

    @Bean("botClient")
    public SimpleTelegramClient botClient(ApplicationEventPublisher eventPublisher) throws Exception {
        ensureTdLightInit();

        APIToken apiToken = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(apiToken);

        Path sessionPath = Paths.get("sessions/bot");
        settings.setDatabaseDirectoryPath(sessionPath);
        settings.setDownloadedFilesDirectoryPath(Paths.get("sessions/shared-downloads"));

        botFactory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder builder = botFactory.builder(settings);

        builder.addUpdatesHandler(update -> {
            try {
                eventPublisher.publishEvent(new BotUpdateEvent(update));
            } catch (Exception e) {
                log.error("Error publishing BotUpdateEvent: {}", e.getMessage());
            }
        });

        log.info("Building bot client (token auth)");
        return builder.build(AuthenticationSupplier.bot(botToken));
    }

    @PreDestroy
    public void destroy() {
        closeFactory(userFactory, "user");
        closeFactory(botFactory, "bot");
    }

    private void closeFactory(SimpleTelegramClientFactory factory, String name) {
        if (factory != null) {
            try {
                factory.close();
                log.info("Closed {} factory", name);
            } catch (Exception e) {
                log.warn("Error closing {} factory: {}", name, e.getMessage());
            }
        }
    }
}
