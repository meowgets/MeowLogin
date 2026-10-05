package com.meowgets.btc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.UUID;

public class TelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private final MeowLogIn plugin;
    private final TelegramClient telegramClient;
    private final TelegramBotsLongPollingApplication botsApplication;

    public TelegramBot(MeowLogIn plugin, String token) {
        this.plugin = plugin;
        this.telegramClient = new OkHttpTelegramClient(token);
        this.botsApplication = new TelegramBotsLongPollingApplication();
        try {
            this.botsApplication.registerBot(token, this);
            plugin.getLogger().info("Telegram bot started.");
        } catch (TelegramApiException e) {
            plugin.getLogger().severe("Failed to start Telegram bot: " + e.getMessage());
        }
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        if (!message.hasText()) return;

        String text = message.getText().trim();
        long chatId = message.getChatId();

        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                if (plugin.isChatLinked(chatId)) {
                    sendMessage(chatId, "Этот Telegram уже привязан к Minecraft-аккаунту.\n" +
                            "Используй /unlink чтобы отвязать.");
                } else {
                    sendMessage(chatId, "Отправь команду /start <код>, который ты видишь в игре.");
                }
                return;
            }
            String code = parts[1].trim();
            String result = plugin.handleTelegramLink(code, chatId);
            sendMessage(chatId, result);
            return;
        }

        if (text.startsWith("/newpass")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                sendMessage(chatId, "Использование: /newpass <новый_пароль>");
                return;
            }
            String result = plugin.changePasswordFromTelegram(chatId, parts[1].trim());
            sendMessage(chatId, result);
            return;
        }

        if (text.startsWith("/unlink")) {
            UUID uuid = plugin.getUuidByChatIdPublic(chatId);
            if (uuid == null) {
                sendMessage(chatId, "Этот Telegram не привязан ни к одному аккаунту.");
                return;
            }
            plugin.unlinkTelegram(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(plugin.msg(p, "telegram-unlinked"));
            }
            sendMessage(chatId, "Telegram успешно отвязан от Minecraft-аккаунта.");
        }
    }

    public void sendMessage(long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            telegramClient.execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram message: " + e.getMessage());
        }
    }

    public void shutdown() {
        try {
            botsApplication.close();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to shutdown Telegram bot: " + e.getMessage());
        }
    }
}