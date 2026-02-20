package com.javaclaw.approval;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DiscordApprovalStrategy implements ApprovalStrategy {

    private static final Logger log = LoggerFactory.getLogger(DiscordApprovalStrategy.class);
    private static final long TIMEOUT_SECONDS = 60;

    private final JDA jda;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingSenders = new ConcurrentHashMap<>();

    public DiscordApprovalStrategy(JDA jda) {
        this.jda = jda;
    }

    @Override
    public boolean approve(String toolName, String arguments, String channelId, String senderId) {
        var dcChannelId = channelId.contains(":") ? channelId.split(":", 2)[1] : channelId;
        var channel = jda.getChannelById(MessageChannel.class, dcChannelId);
        if (channel == null) return false;

        var requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Boolean>();
        pending.put(requestId, future);
        if (senderId != null) pendingSenders.put(requestId, senderId);

        channel.sendMessage(String.format("[APPROVAL] Tool '%s'\nArgs: %s\n60s timeout", toolName, arguments))
                .setActionRow(
                        Button.success("approve:" + requestId, "\u2705 Approve"),
                        Button.danger("deny:" + requestId, "\u274C Deny"))
                .queue();

        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Discord approval timeout/error for tool '{}'", toolName);
            return false;
        } finally {
            pending.remove(requestId);
            pendingSenders.remove(requestId);
        }
    }

    public boolean handleButtonInteraction(ButtonInteractionEvent event) {
        var componentId = event.getComponentId();
        if (!componentId.contains(":")) return false;
        var parts = componentId.split(":", 2);
        var requestId = parts[1];
        var future = pending.get(requestId);
        if (future == null) return false;

        var expectedSender = pendingSenders.get(requestId);
        if (expectedSender != null && !expectedSender.equals(event.getUser().getId())) {
            return false;
        }

        var approved = "approve".equals(parts[0]);
        future.complete(approved);
        event.editMessage("[APPROVAL] " + (approved ? "Approved \u2705" : "Denied \u274C"))
                .setComponents().queue();
        return true;
    }
}
