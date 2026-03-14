package com.yann.autodownload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AutoDownloadApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoDownloadApplication.class, args);
    }

}
