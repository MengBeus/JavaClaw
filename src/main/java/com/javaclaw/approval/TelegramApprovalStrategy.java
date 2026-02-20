package com.javaclaw.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TelegramApprovalStrategy implements ApprovalStrategy {

    private static final Logger log = LoggerFactory.getLogger(TelegramApprovalStrategy.class);
    private static final long TIMEOUT_SECONDS = 60;

    private final TelegramClient telegramClient;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingSenders = new ConcurrentHashMap<>();

    public TelegramApprovalStrategy(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public boolean approve(String toolName, String arguments, String channelId, String senderId) {
        var chatId = channelId.contains(":") ? channelId.split(":", 2)[1] : channelId;
        if (chatId.isBlank()) return false;
        var requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Boolean>();
        pending.put(requestId, future);
        if (senderId != null) pendingSenders.put(requestId, senderId);

        var approveBtn = InlineKeyboardButton.builder()
                .text("\u2705 Approve").callbackData("approve:" + requestId).build();
        var denyBtn = InlineKeyboardButton.builder()
                .text("\u274C Deny").callbackData("deny:" + requestId).build();
        var keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(approveBtn, denyBtn)).build();

        var msg = SendMessage.builder()
                .chatId(chatId)
                .text(String.format("[APPROVAL] Tool '%s'\nArgs: %s\n60s timeout", toolName, arguments))
                .replyMarkup(keyboard).build();
        try {
            telegramClient.execute(msg);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Telegram approval timeout/error for tool '{}'", toolName);
            return false;
        } finally {
            pending.remove(requestId);
            pendingSenders.remove(requestId);
        }
    }

    public boolean handleCallback(CallbackQuery callback) {
        var data = callback.getData();
        if (data == null || !data.contains(":")) return false;
        var parts = data.split(":", 2);
        var requestId = parts[1];
        var future = pending.get(requestId);
        if (future == null) return false;

        var expectedSender = pendingSenders.get(requestId);
        var callbackUserId = callback.getFrom() != null ? String.valueOf(callback.getFrom().getId()) : null;
        if (expectedSender != null && !expectedSender.equals(callbackUserId)) {
            return false;
        }

        var approved = "approve".equals(parts[0]);
        future.complete(approved);

        // Edit message to show result
        try {
            var result = "approve".equals(parts[0]) ? "Approved \u2705" : "Denied \u274C";
            telegramClient.execute(EditMessageText.builder()
                    .chatId(String.valueOf(callback.getMessage().getChatId()))
                    .messageId(callback.getMessage().getMessageId())
                    .text("[APPROVAL] " + result).build());
        } catch (Exception e) {
            log.warn("Failed to edit approval message", e);
        }
        return true;
    }
}
