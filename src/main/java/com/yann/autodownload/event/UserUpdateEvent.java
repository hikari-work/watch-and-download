package com.yann.autodownload.event;

import it.tdlight.jni.TdApi;

public record UserUpdateEvent(TdApi.Update update) {
}
