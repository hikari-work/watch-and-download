package com.yann.autodownload.model;

import it.tdlight.jni.TdApi;

public enum ContentType {
    PHOTO,
    VIDEO,
    DOCUMENT,
    AUDIO,
    ANIMATION,
    VOICE,
    VIDEO_NOTE,
    STICKER;

    /**
     * Returns the ContentType matching the given TdApi.MessageContent, or null if not recognized.
     */
    public static ContentType fromMessageContent(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto) {
            return PHOTO;
        } else if (content instanceof TdApi.MessageVideo) {
            return VIDEO;
        } else if (content instanceof TdApi.MessageDocument) {
            return DOCUMENT;
        } else if (content instanceof TdApi.MessageAudio) {
            return AUDIO;
        } else if (content instanceof TdApi.MessageAnimation) {
            return ANIMATION;
        } else if (content instanceof TdApi.MessageVoiceNote) {
            return VOICE;
        } else if (content instanceof TdApi.MessageVideoNote) {
            return VIDEO_NOTE;
        } else if (content instanceof TdApi.MessageSticker) {
            return STICKER;
        }
        return null;
    }
}
