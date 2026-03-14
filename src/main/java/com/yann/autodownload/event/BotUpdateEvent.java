package com.yann.autodownload.event;

import it.tdlight.jni.TdApi;

public record BotUpdateEvent(TdApi.Update update) {
}
