package com.yann.autodownload.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yann.autodownload.model.WatchConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Slf4j
@Service
public class ConfigService {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    @Value("${config.file.path:config.json}")
    private String configFilePath;

    @Value("${user.id}")
    private long ownerUserId;

    private WatchConfig config;

    @PostConstruct
    public void loadConfig() {
        File file = new File(configFilePath);
        if (file.exists() && file.length() > 0) {
            try {
                config = objectMapper.readValue(file, WatchConfig.class);
                log.info("Loaded config from {}: mirrorGroupId={}, entries={}",
                        configFilePath, config.getMirrorGroupId(), config.getEntries().size());
            } catch (IOException e) {
                log.error("Failed to parse config from {}, creating new config", configFilePath, e);
                config = createDefaultConfig();
            }
        } else {
            log.info("Config file missing or empty at {}, creating new config", configFilePath);
            config = createDefaultConfig();
            saveConfig(); // write default config immediately so the file is never empty again
        }
    }

    private WatchConfig createDefaultConfig() {
        WatchConfig newConfig = new WatchConfig();
        newConfig.setOwnerUserId(ownerUserId);
        return newConfig;
    }

    public synchronized void saveConfig() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(configFilePath), config);
            log.debug("Config saved to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to save config to {}", configFilePath, e);
        }
    }

    public synchronized WatchConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(WatchConfig newConfig) {
        this.config = newConfig;
        saveConfig();
    }

    public String getConfigFilePath() {
        return configFilePath;
    }
}
