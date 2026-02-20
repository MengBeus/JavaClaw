package com.javaclaw.channels;

import com.javaclaw.shared.model.InboundMessage;
import com.javaclaw.shared.model.OutboundMessage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class DiscordAdapter extends ListenerAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordAdapter.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final String botToken;
    private JDA jda;
    private MessageSink sink;

    public DiscordAdapter(String botToken) {
        this.botToken = botToken;
    }

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public void start(MessageSink sink) {
        this.sink = sink;
        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(this)
                    .build();
            jda.awaitReady();
            log.info("Discord bot started");
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Discord bot", e);
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        var message = event.getMessage();
        // 只处理 DM 或 @提及
        boolean isDm = !event.isFromGuild();
        boolean isMentioned = jda.getSelfUser() != null && message.getMentions().isMentioned(jda.getSelfUser());
        if (!isDm && !isMentioned) return;

        var content = message.getContentDisplay();
        var senderId = event.getAuthor().getId();
        var channelId = event.getChannel().getId();
        sink.accept(new InboundMessage(senderId, "discord:" + channelId, content, Instant.now()));
    }

    @Override
    public void send(OutboundMessage msg) {
        var channelId = msg.channelId().replace("discord:", "");
        var channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Discord channel not found: {}", channelId);
            return;
        }
        var text = msg.content();
        for (int i = 0; i < text.length(); i += MAX_MESSAGE_LENGTH) {
            var chunk = text.substring(i, Math.min(i + MAX_MESSAGE_LENGTH, text.length()));
            channel.sendMessage(chunk).queue(
                    null,
                    err -> log.error("Failed to send Discord message to {}", channelId, err)
            );
        }
    }

    @Override
    public void stop() {
        if (jda != null) {
            jda.shutdown();
        }
    }
}
