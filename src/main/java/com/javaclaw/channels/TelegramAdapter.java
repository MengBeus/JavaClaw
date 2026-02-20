package com.javaclaw.channels;

import com.javaclaw.approval.TelegramApprovalStrategy;
import com.javaclaw.shared.model.InboundMessage;
import com.javaclaw.shared.model.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;

public class TelegramAdapter implements ChannelAdapter, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final String botToken;
    private TelegramClient telegramClient;
    private TelegramBotsLongPollingApplication bot;
    private MessageSink sink;
    private TelegramApprovalStrategy approvalStrategy;

    public TelegramAdapter(String botToken) {
        this.botToken = botToken;
    }

    public TelegramClient getTelegramClient() {
        return telegramClient;
    }

    public void setApprovalStrategy(TelegramApprovalStrategy strategy) {
        this.approvalStrategy = strategy;
    }

    @Override
    public String id() {
        return "telegram";
    }

    @Override
    public void start(MessageSink sink) {
        this.sink = sink;
        this.telegramClient = new OkHttpTelegramClient(botToken);
        try {
            bot = new TelegramBotsLongPollingApplication();
            bot.registerBot(botToken, this);
            log.info("Telegram bot started");
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Telegram bot", e);
        }
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery() && approvalStrategy != null) {
            approvalStrategy.handleCallback(update.getCallbackQuery());
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        var msg = update.getMessage();
        if (msg.getFrom() == null) return;
        var senderId = String.valueOf(msg.getFrom().getId());
        var chatId = String.valueOf(msg.getChatId());
        var inbound = new InboundMessage(senderId, "telegram:" + chatId, msg.getText(), Instant.now());
        Thread.startVirtualThread(() -> sink.accept(inbound));
    }

    @Override
    public void send(OutboundMessage msg) {
        var chatId = msg.channelId().replace("telegram:", "");
        var text = msg.content();
        // 超 4096 字符自动分段
        for (int i = 0; i < text.length(); i += MAX_MESSAGE_LENGTH) {
            var chunk = text.substring(i, Math.min(i + MAX_MESSAGE_LENGTH, text.length()));
            try {
                telegramClient.execute(new SendMessage(chatId, chunk));
            } catch (Exception e) {
                log.error("Failed to send Telegram message to {}", chatId, e);
            }
        }
    }

    @Override
    public void stop() {
        if (bot != null) {
            try {
                bot.close();
            } catch (Exception e) {
                log.error("Failed to stop Telegram bot", e);
            }
        }
    }
}
