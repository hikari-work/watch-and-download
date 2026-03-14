package com.yann.autodownload.service;

import com.yann.autodownload.event.BotUpdateEvent;
import com.yann.autodownload.model.WatchEntry;
import com.yann.autodownload.util.HtmlUtil;
import it.tdlight.client.Result;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DownloadService {

    private static final long MAX_FILE_SIZE       = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final long PROGRESS_MIN_SIZE   = 5L * 1024 * 1024;        // 5 MB threshold
    private static final long PROGRESS_INTERVAL   = 10;                       // seconds

    private final SimpleTelegramClient userClient;
    private final SimpleTelegramClient botClient;

    @Value("${user.id}")
    private long ownerUserId;

    // Pending async downloads keyed by TDLib file ID
    private final ConcurrentHashMap<Integer, PendingDownload> pendingDownloads = new ConcurrentHashMap<>();

    /**
     * Maps local (unconfirmed) bot message ID → CompletableFuture that resolves to the
     * server-confirmed message ID via UpdateMessageSendSucceeded.
     * TDLib returns a local ID from SendMessage immediately; the real server ID
     * (needed for EditMessageText) arrives in the subsequent update event.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<Long>> pendingMsgIds = new ConcurrentHashMap<>();

    private final ScheduledExecutorService progressScheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "dl-progress");
                t.setDaemon(true);
                return t;
            });

    /** Download result: local file path + the bot message ID used for progress updates. */
    private record DownloadResult(String path, long progressMsgId) {}

    private record PendingDownload(
            CompletableFuture<String> pathFuture,
            ScheduledFuture<?>        progressTask,
            long                      expectedSize,
            String                    description,
            long                      progressMsgId   // bot message ID to edit
    ) {}

    public DownloadService(@Qualifier("userClient") SimpleTelegramClient userClient,
                           @Qualifier("botClient") SimpleTelegramClient botClient) {
        this.userClient = userClient;
        this.botClient  = botClient;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    public void processAndForward(TdApi.Message message, WatchEntry entry, long mirrorGroupId) {
        TdApi.MessageContent content = message.content;
        TdApi.File           file    = getFileFromContent(content);
        if (file == null) {
            log.warn("No file in content type: {}", content.getClass().getSimpleName());
            return;
        }

        String caption     = extractCaption(content);
        String description = entry.getSourceGroupName() + " · "
                + content.getClass().getSimpleName().replace("Message", "");

        try {
            DownloadResult dl = downloadFile(file.id, file.expectedSize, description);
            if (dl.path() == null) {
                editOrSendProgress(dl.progressMsgId(),
                        html("❌ <b>Download failed</b>\n<i>" + esc(description) + "</i>"));
                return;
            }

            editOrSendProgress(dl.progressMsgId(),
                    html("⬆️ <b>Uploading</b>\n<i>" + esc(description) + "</i>\n<code>"
                            + formatSize(new File(dl.path()).length()) + "</code>"));

            TdApi.File thumbnailFile = getThumbnailFile(content);
            String thumbnailPath = thumbnailFile != null ? downloadThumbnailSync(thumbnailFile) : null;

            long fileSize = new File(dl.path()).length();
            if (fileSize > MAX_FILE_SIZE) {
                log.info("File {} bytes > 2 GB, splitting", fileSize);
                splitAndSend(dl.path(), caption, mirrorGroupId, entry.getTopicId(),
                        description, dl.progressMsgId());
            } else {
                sendMedia(dl.path(), thumbnailPath, caption, mirrorGroupId,
                        entry.getTopicId(), content, description, dl.progressMsgId());
            }
        } catch (Exception e) {
            log.error("Error processing message from group {}", message.chatId, e);
        }
    }

    /**
     * Listens for bot account updates. When the bot sends a message, TDLib first assigns a local
     * message ID and later fires UpdateMessageSendSucceeded with the server-confirmed ID.
     * We resolve the pending future so that sendProgressMessage() can return the correct ID.
     */
    @EventListener
    public void onBotUpdate(BotUpdateEvent event) {
        if (event.update() instanceof TdApi.UpdateMessageSendSucceeded update) {
            CompletableFuture<Long> future = pendingMsgIds.remove(update.oldMessageId);
            if (future != null) {
                log.debug("Message ID confirmed: local={} → server={}", update.oldMessageId, update.message.id);
                future.complete(update.message.id);
            }
        }
    }

    /** Called by WatchService directly (no executor) when UpdateFile arrives for the user client. */
    public void onFileUpdate(TdApi.UpdateFile update) {
        PendingDownload pd = pendingDownloads.get(update.file.id);
        if (pd == null) return;

        TdApi.LocalFile local = update.file.local;
        if (local == null) return;

        if (local.isDownloadingCompleted && local.path != null && !local.path.isEmpty()) {
            PendingDownload removed = pendingDownloads.remove(update.file.id);
            if (removed != null) {
                removed.progressTask().cancel(false);
                log.info("Download complete: fileId={} path={}", update.file.id, local.path);
                removed.pathFuture().complete(local.path);
            }
        } else if (!local.isDownloadingActive && !local.isDownloadingCompleted
                   && local.downloadedSize == 0) {
            PendingDownload removed = pendingDownloads.remove(update.file.id);
            if (removed != null) {
                removed.progressTask().cancel(false);
                removed.pathFuture().completeExceptionally(
                        new RuntimeException("Download stopped for file " + update.file.id));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Download
    // ──────────────────────────────────────────────────────────────────────────

    private DownloadResult downloadFile(int fileId, long expectedSize, String description) {
        long msgId = (expectedSize > PROGRESS_MIN_SIZE)
                ? sendProgressMessage(html("📥 <b>Queued</b>\n<i>" + esc(description) + "</i>\n<code>"
                        + formatSize(expectedSize) + "</code>"))
                : 0L;

        CompletableFuture<String> pathFuture = new CompletableFuture<>();

        ScheduledFuture<?> progressTask = (msgId > 0)
                ? progressScheduler.scheduleAtFixedRate(
                    () -> pollAndEditProgress(fileId, expectedSize, description, msgId),
                    PROGRESS_INTERVAL, PROGRESS_INTERVAL, TimeUnit.SECONDS)
                : progressScheduler.schedule(() -> {}, 0, TimeUnit.SECONDS);

        pendingDownloads.put(fileId,
                new PendingDownload(pathFuture, progressTask, expectedSize, description, msgId));

        TdApi.DownloadFile req = new TdApi.DownloadFile();
        req.fileId      = fileId;
        req.priority    = 32;
        req.offset      = 0;
        req.limit       = 0;
        req.synchronous = false;
        userClient.send(req, (Result<TdApi.File> result) -> {
            if (result.isError()) {
                PendingDownload pd = pendingDownloads.remove(fileId);
                if (pd != null) {
                    pd.progressTask().cancel(false);
                    pd.pathFuture().completeExceptionally(
                            new RuntimeException("DownloadFile error: " + result.getError().message));
                }
                return;
            }
            TdApi.File f = result.get();
            if (f.local != null && f.local.isDownloadingCompleted
                    && f.local.path != null && !f.local.path.isEmpty()) {
                PendingDownload pd = pendingDownloads.remove(fileId);
                if (pd != null) {
                    pd.progressTask().cancel(false);
                    pd.pathFuture().complete(f.local.path);
                }
            }
        });

        try {
            String path = pathFuture.get(24, TimeUnit.HOURS);
            return new DownloadResult(path, msgId);
        } catch (Exception e) {
            pendingDownloads.remove(fileId);
            log.error("Download failed fileId={}: {}", fileId, e.getMessage());
            return new DownloadResult(null, msgId);
        }
    }

    /** Downloads thumbnail synchronously (best-effort, short timeout). */
    public String downloadThumbnailSync(TdApi.File thumbnailFile) {
        if (thumbnailFile == null) return null;
        if (thumbnailFile.local != null && thumbnailFile.local.isDownloadingCompleted
                && thumbnailFile.local.path != null && !thumbnailFile.local.path.isEmpty()) {
            return thumbnailFile.local.path;
        }
        try {
            TdApi.DownloadFile req = new TdApi.DownloadFile();
            req.fileId      = thumbnailFile.id;
            req.priority    = 1;
            req.offset      = 0;
            req.limit       = 0;
            req.synchronous = true;
            TdApi.File result = userClient.send(req).get(60, TimeUnit.SECONDS);
            return (result.local != null && result.local.isDownloadingCompleted)
                    ? result.local.path : null;
        } catch (Exception e) {
            log.warn("Thumbnail download failed: {}", e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Progress messaging — single message, edited in place
    // ──────────────────────────────────────────────────────────────────────────

    private long sendProgressMessage(TdApi.FormattedText formattedText) {
        if (ownerUserId == 0) return 0;
        try {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = formattedText;
            TdApi.SendMessage req = new TdApi.SendMessage();
            req.chatId              = ownerUserId;
            req.inputMessageContent = content;

            // Register the future BEFORE sending to avoid a race where
            // UpdateMessageSendSucceeded arrives before we register.
            CompletableFuture<Long> serverIdFuture = new CompletableFuture<>();

            TdApi.Message msg = botClient.send(req).get(10, TimeUnit.SECONDS);
            pendingMsgIds.put(msg.id, serverIdFuture);

            // Wait briefly for the server-confirmed message ID.
            // TDLib sends the local ID first, then UpdateMessageSendSucceeded with
            // the real server ID (which differs by a factor of 2^20 from the local one).
            try {
                long confirmedId = serverIdFuture.get(5, TimeUnit.SECONDS);
                log.debug("Using server-confirmed message ID {} for progress tracking", confirmedId);
                return confirmedId;
            } catch (TimeoutException e) {
                // UpdateMessageSendSucceeded never came — local and server IDs are the same
                // (can happen when TDLib returns the confirmed ID directly for bots).
                pendingMsgIds.remove(msg.id);
                return msg.id;
            }
        } catch (Exception e) {
            log.warn("Failed to send initial progress message: {}", e.getMessage());
            return 0;
        }
    }

    private void pollAndEditProgress(int fileId, long expectedSize,
                                     String description, long msgId) {
        userClient.send(new TdApi.GetFile(fileId), (Result<TdApi.File> result) -> {
            if (result.isError()) return;
            TdApi.File f      = result.get();
            long downloaded   = (f.local != null) ? f.local.downloadedSize : 0;
            long total        = (f.expectedSize > 0) ? f.expectedSize : expectedSize;
            if (total <= 0) return;

            double pct  = downloaded * 100.0 / total;
            int    bars = (int) (pct / 10);
            String bar  = "█".repeat(bars) + "░".repeat(10 - bars);
            String text = "📥 <b>" + esc(description) + "</b>\n"
                    + "<code>[" + bar + "] " + String.format("%.1f", pct) + "%</code>\n"
                    + "<code>" + formatSize(downloaded) + " / " + formatSize(total) + "</code>";
            editProgressMsg(msgId, html(text));
        });
    }

    public void editOrSendProgress(long msgId, TdApi.FormattedText formattedText) {
        if (msgId > 0) {
            editProgressMsg(msgId, formattedText);
        } else if (ownerUserId != 0) {
            sendProgressMessage(formattedText);
        }
    }

    private void editProgressMsg(long msgId, TdApi.FormattedText formattedText) {
        if (msgId == 0 || ownerUserId == 0) return;
        try {
            TdApi.InputMessageText inputText = new TdApi.InputMessageText();
            inputText.text = formattedText;

            TdApi.EditMessageText edit = new TdApi.EditMessageText();
            edit.chatId              = ownerUserId;
            edit.messageId           = msgId;
            edit.inputMessageContent = inputText;

            botClient.send(edit, (Result<TdApi.Message> r) -> {
                if (r.isError()) log.warn("Failed to edit progress msg {}: {}", msgId, r.getError().message);
            });
        } catch (Exception e) {
            log.warn("Exception editing progress msg: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Send helpers
    // ──────────────────────────────────────────────────────────────────────────

    public void sendMedia(String localPath, String thumbnailPath, String caption,
                          long mirrorGroupId, long topicId,
                          TdApi.MessageContent originalContent,
                          String description, long progressMsgId) {
        TdApi.InputMessageContent inputContent =
                buildInputContent(localPath, thumbnailPath, caption, originalContent);

        TdApi.SendMessage sendReq = new TdApi.SendMessage();
        sendReq.chatId              = mirrorGroupId;
        sendReq.messageThreadId     = topicId;
        sendReq.inputMessageContent = inputContent;

        botClient.send(sendReq, (Result<TdApi.Message> result) -> {
            if (result.isError()) {
                log.error("Bot failed to send to group {} topic {}: {}",
                        mirrorGroupId, topicId, result.getError().message);
                // Fallback to user account
                userClient.send(sendReq, (Result<TdApi.Message> r2) -> {
                    if (r2.isError()) {
                        log.error("User fallback also failed: {}", r2.getError().message);
                        editOrSendProgress(progressMsgId,
                                html("❌ <b>Upload failed</b>\n<i>" + esc(description) + "</i>"));
                    } else {
                        log.info("Sent via user account fallback");
                        editOrSendProgress(progressMsgId,
                                html("✅ <b>Mirrored</b>\n<i>" + esc(description) + "</i>"));
                        deleteQuietly(localPath, thumbnailPath);
                    }
                });
            } else {
                log.info("Sent to group {} topic {}", mirrorGroupId, topicId);
                editOrSendProgress(progressMsgId,
                        html("✅ <b>Mirrored</b>\n<i>" + esc(description) + "</i>"));
                deleteQuietly(localPath, thumbnailPath);
            }
        });
    }

    public void splitAndSend(String filePath, String caption,
                             long mirrorGroupId, long topicId,
                             String description, long progressMsgId) {
        File file       = new File(filePath);
        long totalSize  = file.length();
        int  totalParts = (int) Math.ceil((double) totalSize / MAX_FILE_SIZE);
        AtomicInteger completedParts = new AtomicInteger(0);

        try (FileInputStream fis = new FileInputStream(file);
             FileChannel inChannel = fis.getChannel()) {

            for (int i = 0; i < totalParts; i++) {
                long offset  = (long) i * MAX_FILE_SIZE;
                long length  = Math.min(MAX_FILE_SIZE, totalSize - offset);
                File chunk   = new File(file.getParent(), file.getName() + ".part" + (i + 1));

                try (FileOutputStream fos = new FileOutputStream(chunk);
                     FileChannel outChannel = fos.getChannel()) {
                    inChannel.transferTo(offset, length, outChannel);
                }

                String partCaption = "Part " + (i + 1) + "/" + totalParts;
                if (i == 0 && caption != null && !caption.isEmpty()) {
                    partCaption = caption + "\n" + partCaption;
                }

                editOrSendProgress(progressMsgId,
                        html("⬆️ <b>Uploading part " + (i + 1) + "/" + totalParts + "</b>\n"
                                + "<i>" + esc(description) + "</i>"));

                TdApi.InputMessageDocument doc = new TdApi.InputMessageDocument();
                doc.document = new TdApi.InputFileLocal(chunk.getAbsolutePath());
                doc.caption  = new TdApi.FormattedText(partCaption, new TdApi.TextEntity[0]);

                TdApi.SendMessage sendReq = new TdApi.SendMessage();
                sendReq.chatId              = mirrorGroupId;
                sendReq.messageThreadId     = topicId;
                sendReq.inputMessageContent = doc;

                final int partNum = i + 1;
                botClient.send(sendReq, (Result<TdApi.Message> r) -> {
                    if (r.isError()) {
                        log.error("Failed to send chunk {}/{}: {}", partNum, totalParts,
                                r.getError().message);
                    }

                    deleteQuietly(chunk.getAbsolutePath());

                    if (completedParts.incrementAndGet() == totalParts) {
                        deleteQuietly(filePath);
                        editOrSendProgress(progressMsgId,
                                html("✅ <b>Mirrored</b> <code>(" + totalParts + " parts)</code>\n"
                                        + "<i>" + esc(description) + "</i>"));
                    }
                });
            }
        } catch (IOException e) {
            log.error("Split failed for {}: {}", filePath, e.getMessage(), e);
            editOrSendProgress(progressMsgId,
                    html("❌ <b>Split failed</b>\n<i>" + esc(description) + "</i>"));
        }
    }

    private TdApi.InputMessageContent buildInputContent(String localPath, String thumbnailPath,
                                                         String caption,
                                                         TdApi.MessageContent originalContent) {
        TdApi.FormattedText captionText = new TdApi.FormattedText(
                caption != null ? caption : "", new TdApi.TextEntity[0]);

        TdApi.InputThumbnail thumbnail = null;
        if (thumbnailPath != null && !thumbnailPath.isEmpty()) {
            thumbnail = new TdApi.InputThumbnail();
            thumbnail.thumbnail = new TdApi.InputFileLocal(thumbnailPath);
            thumbnail.width  = 0;
            thumbnail.height = 0;
        }

        if (originalContent instanceof TdApi.MessagePhoto) {
            TdApi.InputMessagePhoto p = new TdApi.InputMessagePhoto();
            p.photo     = new TdApi.InputFileLocal(localPath);
            p.thumbnail = thumbnail;
            p.caption   = captionText;
            p.width     = 0;
            p.height    = 0;
            return p;

        } else if (originalContent instanceof TdApi.MessageVideo v) {
            TdApi.InputMessageVideo vid = new TdApi.InputMessageVideo();
            vid.video             = new TdApi.InputFileLocal(localPath);
            vid.thumbnail         = thumbnail;
            vid.caption           = captionText;
            vid.duration          = v.video.duration;
            vid.width             = v.video.width;
            vid.height            = v.video.height;
            vid.supportsStreaming  = true;
            return vid;

        } else if (originalContent instanceof TdApi.MessageAudio a) {
            TdApi.InputMessageAudio aud = new TdApi.InputMessageAudio();
            aud.audio              = new TdApi.InputFileLocal(localPath);
            aud.albumCoverThumbnail = thumbnail;
            aud.caption            = captionText;
            aud.duration           = a.audio.duration;
            aud.title              = a.audio.title;
            aud.performer          = a.audio.performer;
            return aud;

        } else if (originalContent instanceof TdApi.MessageAnimation an) {
            TdApi.InputMessageAnimation anim = new TdApi.InputMessageAnimation();
            anim.animation = new TdApi.InputFileLocal(localPath);
            anim.thumbnail = thumbnail;
            anim.caption   = captionText;
            anim.duration  = an.animation.duration;
            anim.width     = an.animation.width;
            anim.height    = an.animation.height;
            return anim;

        } else if (originalContent instanceof TdApi.MessageVoiceNote vn) {
            TdApi.InputMessageVoiceNote voice = new TdApi.InputMessageVoiceNote();
            voice.voiceNote = new TdApi.InputFileLocal(localPath);
            voice.duration  = vn.voiceNote.duration;
            voice.waveform  = vn.voiceNote.waveform;
            voice.caption   = captionText;
            return voice;

        } else if (originalContent instanceof TdApi.MessageVideoNote vn) {
            TdApi.InputMessageVideoNote videoNote = new TdApi.InputMessageVideoNote();
            videoNote.videoNote = new TdApi.InputFileLocal(localPath);
            videoNote.thumbnail = thumbnail;
            videoNote.duration  = vn.videoNote.duration;
            videoNote.length    = vn.videoNote.length;
            return videoNote;

        } else {
            TdApi.InputMessageDocument doc = new TdApi.InputMessageDocument();
            doc.document = new TdApi.InputFileLocal(localPath);
            doc.thumbnail = thumbnail;
            doc.caption   = captionText;
            return doc;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Extraction helpers
    // ──────────────────────────────────────────────────────────────────────────

    public String extractCaption(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto p && p.caption != null)    return p.caption.text;
        if (content instanceof TdApi.MessageVideo v && v.caption != null)    return v.caption.text;
        if (content instanceof TdApi.MessageDocument d && d.caption != null) return d.caption.text;
        if (content instanceof TdApi.MessageAudio a && a.caption != null)    return a.caption.text;
        if (content instanceof TdApi.MessageAnimation an && an.caption != null) return an.caption.text;
        if (content instanceof TdApi.MessageVoiceNote vn && vn.caption != null) return vn.caption.text;
        return "";
    }

    private TdApi.File getFileFromContent(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto p) {
            TdApi.PhotoSize largest = null;
            for (TdApi.PhotoSize size : p.photo.sizes) {
                if (largest == null || size.photo.expectedSize > largest.photo.expectedSize)
                    largest = size;
            }
            return largest != null ? largest.photo : null;
        }
        if (content instanceof TdApi.MessageVideo v)     return v.video.video;
        if (content instanceof TdApi.MessageDocument d)  return d.document.document;
        if (content instanceof TdApi.MessageAudio a)     return a.audio.audio;
        if (content instanceof TdApi.MessageAnimation an) return an.animation.animation;
        if (content instanceof TdApi.MessageVoiceNote vn) return vn.voiceNote.voice;
        if (content instanceof TdApi.MessageVideoNote vn) return vn.videoNote.video;
        return null;
    }

    private TdApi.File getThumbnailFile(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto p) {
            return (p.photo.sizes != null && p.photo.sizes.length > 1)
                    ? p.photo.sizes[p.photo.sizes.length - 2].photo : null;
        }
        if (content instanceof TdApi.MessageVideo v && v.video.thumbnail != null)
            return v.video.thumbnail.file;
        if (content instanceof TdApi.MessageDocument d && d.document.thumbnail != null)
            return d.document.thumbnail.file;
        if (content instanceof TdApi.MessageAudio a && a.audio.albumCoverThumbnail != null)
            return a.audio.albumCoverThumbnail.file;
        if (content instanceof TdApi.MessageAnimation an && an.animation.thumbnail != null)
            return an.animation.thumbnail.file;
        if (content instanceof TdApi.MessageVideoNote vn && vn.videoNote.thumbnail != null)
            return vn.videoNote.thumbnail.file;
        return null;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)               return bytes + " B";
        if (bytes < 1024 * 1024)        return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTML formatting helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Converts an HTML string to a FormattedText using a pure-Java parser (no TDLib call). */
    private static TdApi.FormattedText html(String htmlText) {
        return HtmlUtil.parse(htmlText);
    }

    private static String esc(String text) {
        return HtmlUtil.esc(text);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File cleanup
    // ──────────────────────────────────────────────────────────────────────────

    private void deleteQuietly(String... paths) {
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            File f = new File(path);
            if (f.exists()) {
                if (f.delete()) {
                    log.debug("Deleted local file: {}", path);
                } else {
                    log.warn("Could not delete local file: {}", path);
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        progressScheduler.shutdownNow();
        pendingDownloads.forEach((id, pd) -> {
            pd.progressTask().cancel(false);
            pd.pathFuture().cancel(true);
        });
        pendingDownloads.clear();
    }
}
