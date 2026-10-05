package com.meowgets.btc;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.Base64;

public class MeowLogIn extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Connection connection;
    private final Map<UUID, BukkitTask> kickTasks = new HashMap<>();
    private final Map<UUID, Boolean> authenticated = new HashMap<>();
    private final Map<UUID, Location> preAuthLocations = new HashMap<>();
    private final Map<UUID, String> playerLanguages = new HashMap<>();
    private final Map<String, UUID> linkCodes = new HashMap<>();
    private final Set<UUID> telegramHintShown = new HashSet<>();

    private final Map<String, Map<String, String>> languages = new HashMap<>();
    private final Map<String, String> languageNames = new HashMap<>();
    private final Map<String, Boolean> languageValid = new HashMap<>();

    private String defaultLang = "en";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Set<String> allowedKeys = new HashSet<>();

    private TelegramBot telegramBot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        defaultLang = getConfig().getString("default-language", "en");
        connectDatabase();
        loadAllLanguages();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("meowlogin") != null) {
            getCommand("meowlogin").setExecutor(this);
            getCommand("meowlogin").setTabCompleter(this);
        }
        if (getCommand("ml") != null) {
            getCommand("ml").setExecutor(this);
            getCommand("ml").setTabCompleter(this);
        }
        if (getCommand("login") != null) {
            getCommand("login").setExecutor(this);
        }
        if (getCommand("register") != null) {
            getCommand("register").setExecutor(this);
        }
        if (getCommand("resetpassword") != null) {
            getCommand("resetpassword").setExecutor(this);
        }
        if (getCommand("link") != null) {
            getCommand("link").setExecutor(this);
        }
        if (getCommand("unlink") != null) {
            getCommand("unlink").setExecutor(this);
        }
        if (getCommand("unreg") != null) {
            getCommand("unreg").setExecutor(this);
            getCommand("unreg").setTabCompleter(this);
        }

        if (getConfig().getBoolean("telegram.enabled", false)) {
            String token = getConfig().getString("telegram.token", "");
            if (token != null && !token.isEmpty() && !token.equals("ТВОЙ_ТОКЕН_ОТ_BOTFATHER")) {
                telegramBot = new TelegramBot(this, token);
            } else {
                getLogger().warning("Telegram token is not set. Telegram features disabled.");
            }
        }

        getLogger().info("MeowLogIn enabled. Loaded languages: " + languages.keySet());
    }

    @Override
    public void onDisable() {
        if (telegramBot != null) {
            telegramBot.shutdown();
        }
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to close DB: " + e.getMessage());
        }
        getLogger().info("MeowLogIn disabled.");
    }

    public TelegramBot getTelegramBot() {
        return telegramBot;
    }

    // ========== БАЗА ==========
    private void connectDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            File dbFile = new File(getDataFolder(), "users.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, password_hash TEXT NOT NULL, salt TEXT NOT NULL, auth_mode TEXT NOT NULL DEFAULT 'DIALOG')");
                stmt.execute("CREATE TABLE IF NOT EXISTS telegram_links (uuid TEXT PRIMARY KEY, chat_id INTEGER NOT NULL)");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tg_chat ON telegram_links(chat_id)");
            }
            getLogger().info("Database connected.");
        } catch (SQLException e) {
            getLogger().severe("Failed to connect to database: " + e.getMessage());
        }
    }

    private boolean isRegistered(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public boolean isRegisteredPublic(UUID uuid) {
        return isRegistered(uuid);
    }

    private void registerUser(UUID uuid, String hash, String salt, String mode) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO users (uuid, password_hash, salt, auth_mode) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, mode);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().severe("Register error: " + e.getMessage()); }
    }

    private void updatePassword(UUID uuid, String hash, String salt) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET password_hash = ?, salt = ? WHERE uuid = ?")) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().severe("UpdatePassword error: " + e.getMessage()); }
    }

    private String getPasswordHash(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT password_hash FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("password_hash") : null; }
        } catch (SQLException e) { return null; }
    }

    private String getSalt(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT salt FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("salt") : null; }
        } catch (SQLException e) { return null; }
    }

    private String getAuthMode(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT auth_mode FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("auth_mode") : null; }
        } catch (SQLException e) { return null; }
    }

    private void setAuthMode(UUID uuid, String mode) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET auth_mode = ? WHERE uuid = ?")) {
            ps.setString(1, mode);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().severe("SetAuthMode error: " + e.getMessage()); }
    }

    // ========== TELEGRAM LINKS ==========
    private void saveTelegramLink(UUID uuid, long chatId) {
        try {
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM telegram_links WHERE chat_id = ? AND uuid != ?")) {
                del.setLong(1, chatId);
                del.setString(2, uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM telegram_links WHERE uuid = ?")) {
                del.setString(1, uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO telegram_links (uuid, chat_id) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, chatId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            getLogger().severe("SaveTelegramLink error: " + e.getMessage());
        }
    }

    private Long getTelegramChatId(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT chat_id FROM telegram_links WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong("chat_id") : null; }
        } catch (SQLException e) { return null; }
    }

    private UUID getUuidByChatId(long chatId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM telegram_links WHERE chat_id = ?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
                return null;
            }
        } catch (SQLException e) { return null; }
    }

    public UUID getUuidByChatIdPublic(long chatId) {
        return getUuidByChatId(chatId);
    }

    public boolean isChatLinked(long chatId) {
        return getUuidByChatId(chatId) != null;
    }

    public boolean unlinkTelegram(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM telegram_links WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            getLogger().severe("UnlinkTelegram error: " + e.getMessage());
            return false;
        }
    }

    public boolean unregisterUser(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            boolean removed = ps.executeUpdate() > 0;
            if (removed) unlinkTelegram(uuid);
            return removed;
        } catch (SQLException e) {
            getLogger().severe("Unregister error: " + e.getMessage());
            return false;
        }
    }

    public String handleTelegramLink(String code, long chatId) {
        UUID uuid = linkCodes.remove(code.toUpperCase());
        if (uuid == null) {
            return "Неверный или устаревший код. Возьми новый код в игре командой /link.";
        }
        saveTelegramLink(uuid, chatId);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(msg(player, "telegram-linked"));
        }
        return "Аккаунт успешно привязан!";
    }

    public String changePasswordFromTelegram(long chatId, String newPassword) {
        UUID uuid = getUuidByChatId(chatId);
        if (uuid == null) {
            return "Этот Telegram-аккаунт не привязан ни к одному Minecraft-аккаунту. Используй /link в игре.";
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return "Пароль не может быть пустым.";
        }
        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        updatePassword(uuid, hash, salt);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            if (isAuthenticated(player)) {
                player.sendMessage(msg(player, "password-changed-online"));
            } else {
                player.sendMessage(msg(player, "password-changed-not-auth"));
            }
        }
        return "Пароль успешно изменён! Зайди в игру и используй /login <новый пароль>.";
    }

    // ========== ХЕШ ==========
    private String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest((password + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private boolean verifyPassword(String password, String salt, String hash) {
        return hashPassword(password, salt).equals(hash);
    }

    // ========== ЯЗЫКИ ==========
    private Map<String, String> getBuiltinEnglish() {
        Map<String, String> m = new HashMap<>();
        m.put("language-name", "English");
        m.put("enter-password", "Enter your password.");
        m.put("enter-password-confirm", "Enter your password and confirm it.");
        m.put("password-field", "Password");
        m.put("confirm-field", "Confirm password");
        m.put("login-button", "Login");
        m.put("register-button", "Register");
        m.put("switch-to-chat", "Switch to chat");
        m.put("forgot-password", "Forgot password?");
        m.put("auth-success", "§aAuthentication successful!");
        m.put("register-success", "§aYou have been registered!");
        m.put("wrong-password", "§cWrong password.");
        m.put("passwords-dont-match", "§cPasswords do not match.");
        m.put("empty-password", "§cPassword cannot be empty.");
        m.put("already-registered", "§cYou are already registered.");
        m.put("not-registered", "§cYou are not registered.");
        m.put("command-denied", "§cThis command is unavailable before authentication.");
        m.put("chat-denied", "§cPlease authenticate first.");
        m.put("timeout-kick", "You did not authenticate in time.");
        m.put("mode-switched-to-chat", "§aAuth mode switched to §fchat§a.");
        m.put("mode-switched-to-dialog", "§aAuth mode switched to §fdialog§a.");
        m.put("chat-instruction-login", "§7Enter §f/login <password>§7 or §f/l <password>§7.");
        m.put("chat-instruction-register", "§7Enter §f/register <password> <password>§7 or §f/reg <password> <password>§7.");
        m.put("help-header", "§e=== MeowLogIn ===");
        m.put("help-current-mode", "§7Current mode: §f");
        m.put("help-mode-command", "§7/meowlogin mode §f— switch auth mode");
        m.put("help-login", "§7/login <password> §f— login");
        m.put("help-register", "§7/register <password> <password> §f— register");
        m.put("help-language", "§7/meowlogin lang §f— change language");
        m.put("help-reload", "§7/meowlogin reload §f— reload config and languages");
        m.put("help-link", "§7/link §f— link your Telegram account");
        m.put("language-switched", "§aLanguage changed to §f");
        m.put("language-invalid", "§cInvalid language. Available: ");
        m.put("language-broken", "§cThis language file is broken (wrong keys). Using English. Available: ");
        m.put("lang-denied", "§cYou don't have permission to change language.");
        m.put("reload-denied", "§cYou don't have permission to use this command.");
        m.put("reload-success", "§aConfig and languages reloaded. Languages loaded: ");
        m.put("usage-login", "§cUsage: /login <password>");
        m.put("usage-register", "§cUsage: /register <password> <password>");
        m.put("already-authenticated", "§cYou are already authenticated.");
        m.put("reset-tg-sent", "§aMessage sent to your Telegram. Send /newpass <password> there.");
        m.put("reset-tg-failed", "§cCould not send Telegram message. Check your link.");
        m.put("reset-not-linked", "§cYour account is not linked to Telegram. Use §f/link§c first.");
        m.put("telegram-link-code", "§eSend to bot @{bot}: ");
        m.put("telegram-linked", "§aTelegram successfully linked!");
        m.put("telegram-already-linked", "§cYour Telegram is already linked.");
        m.put("telegram-hint", "§eTip: link Telegram to §f{bot}§e with §f/link§e — you can reset your password via bot.");
        m.put("password-changed-online", "§aPassword changed via Telegram. Use it on your next login.");
        m.put("password-changed-not-auth", "§aPassword changed via Telegram. Use it to log in.");
        m.put("telegram-required", "§cYou must link your Telegram account to play. Use §f/link§c.");
        m.put("telegram-unlinked", "§aTelegram successfully unlinked!");
        m.put("telegram-not-linked", "§cYour Telegram is not linked.");
        m.put("unreg-denied", "§cYou don't have permission to use this command.");
        m.put("unreg-not-found", "§cPlayer not found.");
        m.put("unreg-not-registered", "§cThis player is not registered.");
        m.put("unreg-success", "§aPlayer §f{player}§a has been unregistered.");
        m.put("unreg-kicked", "§cYour account was unregistered by an admin.");
        return m;
    }

    private Map<String, String> getBuiltinRussian() {
        Map<String, String> m = new HashMap<>();
        m.put("language-name", "Русский");
        m.put("enter-password", "Введите пароль.");
        m.put("enter-password-confirm", "Введите пароль и подтвердите его.");
        m.put("password-field", "Пароль");
        m.put("confirm-field", "Подтвердите пароль");
        m.put("login-button", "Войти");
        m.put("register-button", "Зарегистрироваться");
        m.put("switch-to-chat", "Переключить на чат");
        m.put("forgot-password", "Забыли пароль?");
        m.put("auth-success", "§aАвторизация успешна!");
        m.put("register-success", "§aВы успешно зарегистрированы!");
        m.put("wrong-password", "§cНеверный пароль.");
        m.put("passwords-dont-match", "§cПароли не совпадают.");
        m.put("empty-password", "§cПароль не может быть пустым.");
        m.put("already-registered", "§cВы уже зарегистрированы.");
        m.put("not-registered", "§cВы не зарегистрированы.");
        m.put("command-denied", "§cЭта команда недоступна до авторизации.");
        m.put("chat-denied", "§cСначала авторизуйтесь.");
        m.put("timeout-kick", "Вы не авторизовались за отведённое время.");
        m.put("mode-switched-to-chat", "§aРежим авторизации изменён на §fчат§a.");
        m.put("mode-switched-to-dialog", "§aРежим авторизации изменён на §fдиалоговое окно§a.");
        m.put("chat-instruction-login", "§7Введите §f/login <пароль>§7 или §f/l <пароль>§7.");
        m.put("chat-instruction-register", "§7Введите §f/register <пароль> <пароль>§7 или §f/reg <пароль> <пароль>§7.");
        m.put("help-header", "§e=== MeowLogIn ===");
        m.put("help-current-mode", "§7Текущий режим: §f");
        m.put("help-mode-command", "§7/meowlogin mode §f— переключить режим авторизации");
        m.put("help-login", "§7/login <пароль> §f— войти");
        m.put("help-register", "§7/register <пароль> <пароль> §f— зарегистрироваться");
        m.put("help-language", "§7/meowlogin lang §f— сменить язык");
        m.put("help-reload", "§7/meowlogin reload §f— перезагрузить конфиг и языки");
        m.put("help-link", "§7/link §f— привязать Telegram");
        m.put("language-switched", "§aЯзык изменён на §f");
        m.put("language-invalid", "§cНеверный язык. Доступные: ");
        m.put("language-broken", "§cЭтот файл языка повреждён (неверные ключи). Используется английский. Доступные: ");
        m.put("lang-denied", "§cУ вас нет прав на смену языка.");
        m.put("reload-denied", "§cУ вас нет прав на эту команду.");
        m.put("reload-success", "§aКонфиг и языки перезагружены. Загружено языков: ");
        m.put("usage-login", "§cИспользование: /login <пароль>");
        m.put("usage-register", "§cИспользование: /register <пароль> <пароль>");
        m.put("already-authenticated", "§cВы уже авторизованы.");
        m.put("reset-tg-sent", "§aСообщение отправлено в Telegram. Напиши там /newpass <новый_пароль>.");
        m.put("reset-tg-failed", "§cНе удалось отправить сообщение в Telegram. Проверь привязку.");
        m.put("reset-not-linked", "§cТвой аккаунт не привязан к Telegram. Используй §f/link§c.");
        m.put("telegram-link-code", "§eОтправь боту @{bot}: ");
        m.put("telegram-linked", "§aTelegram успешно привязан!");
        m.put("telegram-already-linked", "§cТвой Telegram уже привязан.");
        m.put("telegram-hint", "§eСовет: привяжи Telegram боту §f{bot}§e командой §f/link§e — сможешь сбрасывать пароль через бота.");
        m.put("password-changed-online", "§aПароль изменён через Telegram. Используй его при следующем входе.");
        m.put("password-changed-not-auth", "§aПароль изменён через Telegram. Используй его для входа.");
        m.put("telegram-required", "§cТы должен привязать Telegram, чтобы играть. Используй §f/link§c.");
        m.put("telegram-unlinked", "§aTelegram успешно отвязан!");
        m.put("telegram-not-linked", "§cТвой Telegram не привязан.");
        m.put("unreg-denied", "§cУ тебя нет прав на эту команду.");
        m.put("unreg-not-found", "§cИгрок не найден.");
        m.put("unreg-not-registered", "§cЭтот игрок не зарегистрирован.");
        m.put("unreg-success", "§aИгрок §f{player}§a удалён из базы.");
        m.put("unreg-kicked", "§cТвой аккаунт удалён администратором.");
        return m;
    }

    private void loadAllLanguages() {
        languages.clear();
        languageNames.clear();
        languageValid.clear();

        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) langFolder.mkdirs();
        saveResource("lang/en.yml", false);
        saveResource("lang/ru.yml", false);

        Map<String, String> enDefaults = getBuiltinEnglish();
        Map<String, String> ruDefaults = getBuiltinRussian();

        allowedKeys = new HashSet<>(enDefaults.keySet());

        languages.put("en", new HashMap<>(enDefaults));
        languageNames.put("en", enDefaults.get("language-name"));
        languageValid.put("en", true);

        languages.put("ru", new HashMap<>(ruDefaults));
        languageNames.put("ru", ruDefaults.get("language-name"));
        languageValid.put("ru", true);

        File[] files = langFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String code = file.getName().substring(0, file.getName().length() - 4).toLowerCase();
                if (code.equals("en") || code.equals("ru")) continue;

                FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                boolean valid = true;
                Set<String> fileKeys = cfg.getKeys(false);

                for (String key : fileKeys) {
                    if (!allowedKeys.contains(key)) {
                        valid = false;
                        getLogger().warning("Language file " + file.getName() + " has unknown key: " + key);
                    }
                }
                for (String key : allowedKeys) {
                    if (!cfg.contains(key)) {
                        valid = false;
                        getLogger().warning("Language file " + file.getName() + " is missing key: " + key);
                    }
                }

                Map<String, String> langMap = new HashMap<>(enDefaults);
                for (String key : allowedKeys) {
                    if (cfg.contains(key)) {
                        String val = cfg.getString(key);
                        if (val != null) langMap.put(key, val);
                    }
                }

                languages.put(code, langMap);
                languageNames.put(code, langMap.getOrDefault("language-name", code));
                languageValid.put(code, valid);
            }
        }
    }

    private String getLang(Player player) {
        String lang = playerLanguages.getOrDefault(player.getUniqueId(), defaultLang);
        if (!languages.containsKey(lang)) lang = "en";
        return lang;
    }

    public String msg(Player player, String key) {
        String lang = getLang(player);
        Map<String, String> map = languages.getOrDefault(lang, languages.get("en"));
        return map.getOrDefault(key, "§cMissing: " + key);
    }

    private List<String> getAvailableLanguages() {
        return languages.keySet().stream()
                .filter(code -> Boolean.TRUE.equals(languageValid.get(code)))
                .sorted()
                .toList();
    }

    private String getLanguageDisplayName(String code) {
        return languageNames.getOrDefault(code, code);
    }

    // ========== ДИАЛОГ ==========
    private void showDialog(Player player, boolean isRegistration) {
        DialogBase.Builder baseBuilder = DialogBase.builder(Component.text(isRegistration ? "Регистрация" : "Авторизация"))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE);

        if (isRegistration) {
            baseBuilder.body(List.of(DialogBody.plainMessage(Component.text(msg(player, "enter-password-confirm")))));
            baseBuilder.inputs(List.of(
                    DialogInput.text("password", Component.text(msg(player, "password-field"))).build(),
                    DialogInput.text("confirm", Component.text(msg(player, "confirm-field"))).build()
            ));
        } else {
            baseBuilder.body(List.of(DialogBody.plainMessage(Component.text(msg(player, "enter-password")))));
            baseBuilder.inputs(List.of(
                    DialogInput.text("password", Component.text(msg(player, "password-field"))).build()
            ));
        }

        ActionButton submitButton = ActionButton.create(
                Component.text(isRegistration ? msg(player, "register-button") : msg(player, "login-button")),
                null, 100, DialogAction.customClick(Key.key("meowlogin:submit"), null)
        );
        ActionButton switchButton = ActionButton.create(
                Component.text(msg(player, "switch-to-chat")),
                null, 100, DialogAction.customClick(Key.key("meowlogin:switch"), null)
        );

        if (isRegistration) {
            Dialog dialog = Dialog.create(builder -> builder
                    .empty()
                    .base(baseBuilder.build())
                    .type(DialogType.multiAction(List.of(submitButton, switchButton)).build())
            );
            player.showDialog(dialog);
        } else {
            ActionButton forgotButton = ActionButton.create(
                    Component.text(msg(player, "forgot-password")),
                    null, 100, DialogAction.customClick(Key.key("meowlogin:forgot"), null)
            );
            Dialog dialog = Dialog.create(builder -> builder
                    .empty()
                    .base(baseBuilder.build())
                    .type(DialogType.multiAction(List.of(submitButton, switchButton, forgotButton)).build())
            );
            player.showDialog(dialog);
        }
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.getOrDefault(player.getUniqueId(), false);
    }

    public void showDialogForPlayer(Player player) {
        showDialog(player, !isRegistered(player.getUniqueId()));
    }

    private void sendTelegramReset(Player player) {
        if (telegramBot == null) {
            player.sendMessage(msg(player, "reset-tg-failed"));
            return;
        }
        Long chatId = getTelegramChatId(player.getUniqueId());
        if (chatId == null) {
            player.sendMessage(msg(player, "reset-not-linked"));
            return;
        }
        telegramBot.sendMessage(chatId, "Сброс пароля. Отправь боту команду:\n/newpass <новый_пароль>");
        player.sendMessage(msg(player, "reset-tg-sent"));
    }

    // ========== СОБЫТИЯ ==========
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        authenticated.put(uuid, false);
        preAuthLocations.put(uuid, player.getLocation().clone());

        if (getConfig().getString("spawn-mode", "DEFAULT").equalsIgnoreCase("FIXED")) {
            Location fixed = getFixedLocation();
            if (fixed != null) player.teleport(fixed);
        }
        if (getConfig().getBoolean("block.mob-target", true)) player.setInvisible(true);

        String authMode = getAuthMode(uuid);
        if (authMode == null || authMode.equals("DIALOG")) {
            showDialog(player, !isRegistered(uuid));
        } else {
            player.sendMessage(msg(player, "chat-instruction-login"));
        }

        if (getTelegramChatId(uuid) == null && !telegramHintShown.contains(uuid)) {
            telegramHintShown.add(uuid);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    String botUsername = getConfig().getString("telegram.bot-username", "MeowAuthBot");
                    String hintText = msg(player, "telegram-hint").replace("{bot}", "@" + botUsername);
                    player.sendMessage(Component.text(hintText)
                            .append(Component.text(" §a§l[/link]")
                                    .clickEvent(ClickEvent.copyToClipboard("/link"))
                                    .hoverEvent(HoverEvent.showText(Component.text("§7Нажми, чтобы скопировать команду")))));
                }
            }, 60L);
        }

        int timeout = getConfig().getInt("auth-timeout", 120);
        if (timeout > 0) {
            kickTasks.put(uuid, getServer().getScheduler().runTaskLater(this, () -> {
                if (!isAuthenticated(player)) player.kick(Component.text(msg(player, "timeout-kick")));
            }, timeout * 20L));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        authenticated.remove(uuid);
        preAuthLocations.remove(uuid);
        playerLanguages.remove(uuid);
        telegramHintShown.remove(uuid);
        BukkitTask task = kickTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private Location getFixedLocation() {
        String worldName = getConfig().getString("spawn-location.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("spawn-location.x", 0.0),
                getConfig().getDouble("spawn-location.y", 64.0),
                getConfig().getDouble("spawn-location.z", 0.0),
                (float) getConfig().getDouble("spawn-location.yaw", 0.0),
                (float) getConfig().getDouble("spawn-location.pitch", 0.0));
    }

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof io.papermc.paper.connection.PlayerGameConnection gc)) return;
        Player player = gc.getPlayer();
        if (player == null) return;
        Key actionId = event.getIdentifier();

        if (actionId.equals(Key.key("meowlogin:switch"))) {
            setAuthMode(player.getUniqueId(), "CHAT");
            player.closeDialog();
            player.sendMessage(msg(player, "mode-switched-to-chat"));
            if (!isAuthenticated(player)) {
                player.sendMessage(msg(player, isRegistered(player.getUniqueId()) ? "chat-instruction-login" : "chat-instruction-register"));
            }
            return;
        }

        if (actionId.equals(Key.key("meowlogin:forgot"))) {
            player.closeDialog();
            sendTelegramReset(player);
            return;
        }

        if (actionId.equals(Key.key("meowlogin:submit"))) {
            var response = event.getDialogResponseView();
            if (response == null) return;
            String password = response.getText("password");
            String confirm = response.getText("confirm");

            if (isRegistered(player.getUniqueId())) {
                if (password == null || password.isEmpty()) {
                    player.sendMessage(msg(player, "empty-password"));
                    showDialog(player, false);
                    return;
                }
                handleLogin(player, password);
            } else {
                if (password == null || password.isEmpty() || confirm == null || confirm.isEmpty()) {
                    player.sendMessage(msg(player, "empty-password"));
                    showDialog(player, true);
                    return;
                }
                if (!password.equals(confirm)) {
                    player.sendMessage(msg(player, "passwords-dont-match"));
                    showDialog(player, true);
                    return;
                }
                handleRegister(player, password);
            }
        }
    }

    // ========== ЛОГИН / РЕГИСТРАЦИЯ ==========
    private void handleLogin(Player player, String password) {
        if (isAuthenticated(player)) {
            player.sendMessage(msg(player, "already-authenticated"));
            return;
        }
        UUID uuid = player.getUniqueId();
        String salt = getSalt(uuid);
        String hash = getPasswordHash(uuid);
        if (salt == null || hash == null) {
            player.sendMessage(msg(player, "not-registered"));
            return;
        }
        if (verifyPassword(password, salt, hash)) {
            onAuthSuccess(player, false);
        } else {
            player.sendMessage(msg(player, "wrong-password"));
            if ("DIALOG".equals(getAuthMode(uuid))) showDialog(player, false);
        }
    }

    private void handleRegister(Player player, String password) {
        if (isAuthenticated(player)) {
            player.sendMessage(msg(player, "already-authenticated"));
            return;
        }
        UUID uuid = player.getUniqueId();
        if (isRegistered(uuid)) {
            player.sendMessage(msg(player, "already-registered"));
            return;
        }
        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        registerUser(uuid, hash, salt, "DIALOG");
        player.sendMessage(msg(player, "register-success"));
        onAuthSuccess(player, true);
    }

    private void onAuthSuccess(Player player, boolean fromRegistration) {
        UUID uuid = player.getUniqueId();

        if (getConfig().getBoolean("require-telegram-link", false)
                && getTelegramChatId(uuid) == null) {
            player.sendMessage(msg(player, "telegram-required"));
            return;
        }

        authenticated.put(uuid, true);
        player.setInvisible(false);
        Location saved = preAuthLocations.remove(uuid);
        if (saved != null) player.teleport(saved);
        BukkitTask task = kickTasks.remove(uuid);
        if (task != null) task.cancel();
        if (!fromRegistration) player.sendMessage(msg(player, "auth-success"));
    }

    // ========== КОМАНДЫ ==========
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players.");
            return true;
        }
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("resetpassword")) {
            sendTelegramReset(player);
            return true;
        }

        if (cmd.equals("link")) {
            if (getTelegramChatId(player.getUniqueId()) != null) {
                player.sendMessage(msg(player, "telegram-already-linked"));
                return true;
            }
            String code = generateLinkCode();
            linkCodes.put(code, player.getUniqueId());
            String botUsername = getConfig().getString("telegram.bot-username", "MeowAuthBot");
            String tgCommand = "/start " + code;

            player.sendMessage(Component.text(msg(player, "telegram-link-code").replace("{bot}", botUsername))
                    .append(Component.text("§a§l" + tgCommand)
                            .clickEvent(ClickEvent.copyToClipboard(tgCommand))
                            .hoverEvent(HoverEvent.showText(Component.text("§7Нажми, чтобы скопировать")))));
            return true;
        }

        if (cmd.equals("unlink")) {
            if (unlinkTelegram(player.getUniqueId())) {
                player.sendMessage(msg(player, "telegram-unlinked"));
            } else {
                player.sendMessage(msg(player, "telegram-not-linked"));
            }
            return true;
        }

        if (cmd.equals("unreg")) {
            if (!player.hasPermission("meowlogin.unreg") && !player.isOp()) {
                player.sendMessage(msg(player, "unreg-denied"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage("§cИспользование: /unreg <игрок>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            UUID targetUuid = null;
            String targetName = args[0];

            if (target != null) {
                targetUuid = target.getUniqueId();
                targetName = target.getName();
            } else {
                org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(args[0]);
                if (off.hasPlayedBefore()) {
                    targetUuid = off.getUniqueId();
                    targetName = off.getName() != null ? off.getName() : args[0];
                }
            }

            if (targetUuid == null) {
                player.sendMessage(msg(player, "unreg-not-found"));
                return true;
            }

            if (!isRegistered(targetUuid)) {
                player.sendMessage(msg(player, "unreg-not-registered"));
                return true;
            }

            if (unregisterUser(targetUuid)) {
                player.sendMessage(msg(player, "unreg-success").replace("{player}", targetName));
                if (target != null && target.isOnline()) {
                    target.kick(Component.text(msg(target, "unreg-kicked")));
                }
            } else {
                player.sendMessage("§cНе удалось удалить аккаунт.");
            }
            return true;
        }

        if (cmd.equals("login")) {
            if (isAuthenticated(player)) {
                player.sendMessage(msg(player, "already-authenticated"));
                return true;
            }
            if (args.length < 1) { player.sendMessage(msg(player, "usage-login")); return true; }
            handleLogin(player, args[0]);
            return true;
        }

        if (cmd.equals("register")) {
            if (isAuthenticated(player)) {
                player.sendMessage(msg(player, "already-authenticated"));
                return true;
            }
            if (args.length < 2) { player.sendMessage(msg(player, "usage-register")); return true; }
            if (!args[0].equals(args[1])) {
                player.sendMessage(msg(player, "passwords-dont-match"));
                return true;
            }
            handleRegister(player, args[0]);
            return true;
        }

        if (cmd.equals("meowlogin") || cmd.equals("ml")) {
            if (args.length == 0) {
                String currentMode = getAuthMode(player.getUniqueId());
                if (currentMode == null) currentMode = "DIALOG";
                player.sendMessage(msg(player, "help-header"));
                player.sendMessage(msg(player, "help-current-mode") + currentMode);
                player.sendMessage(msg(player, "help-mode-command"));
                player.sendMessage(msg(player, "help-login"));
                player.sendMessage(msg(player, "help-register"));
                player.sendMessage(msg(player, "help-language"));
                player.sendMessage(msg(player, "help-reload"));
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("mode")) {
                String currentMode = getAuthMode(player.getUniqueId());
                boolean isAuthed = isAuthenticated(player);

                if (currentMode == null) {
                    setAuthMode(player.getUniqueId(), "CHAT");
                    player.closeDialog();
                    player.sendMessage(msg(player, "mode-switched-to-chat"));
                    if (!isAuthed) player.sendMessage(msg(player, "chat-instruction-register"));
                    return true;
                }
                if (currentMode.equals("DIALOG")) {
                    setAuthMode(player.getUniqueId(), "CHAT");
                    player.closeDialog();
                    player.sendMessage(msg(player, "mode-switched-to-chat"));
                    if (!isAuthed) player.sendMessage(msg(player, "chat-instruction-login"));
                } else {
                    setAuthMode(player.getUniqueId(), "DIALOG");
                    player.sendMessage(msg(player, "mode-switched-to-dialog"));
                    if (!isAuthed) showDialogForPlayer(player);
                }
                return true;
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
                if (!player.hasPermission("meowlogin.lang") && !player.isOp()) {
                    player.sendMessage(msg(player, "lang-denied"));
                    return true;
                }
                String lang = args[1].toLowerCase();
                if (!languages.containsKey(lang) || Boolean.FALSE.equals(languageValid.get(lang))) {
                    player.sendMessage(msg(player, "language-invalid") + String.join(", ", getAvailableLanguages()));
                    return true;
                }
                playerLanguages.put(player.getUniqueId(), lang);
                player.sendMessage(msg(player, "language-switched") + getLanguageDisplayName(lang));
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!player.hasPermission("meowlogin.reload") && !player.isOp()) {
                    player.sendMessage(msg(player, "reload-denied"));
                    return true;
                }
                reloadConfig();
                defaultLang = getConfig().getString("default-language", "en");
                loadAllLanguages();
                player.sendMessage(msg(player, "reload-success") + languages.size());
                getLogger().info("Config and languages reloaded by " + player.getName() + ". Languages: " + languages.keySet());
                return true;
            }

            player.sendMessage("§cИспользование: /meowlogin [mode|lang|reload]");
            return true;
        }
        return true;
    }

    private String generateLinkCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        String name = command.getName().toLowerCase();
        if (name.equals("meowlogin") || name.equals("ml")) {
            if (args.length == 1) {
                return List.of("mode", "lang", "reload").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
                return getAvailableLanguages().stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }
        if (name.equals("unreg")) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                        .toList();
            }
        }
        return Collections.emptyList();
    }

    // ========== БЛОКИРОВКИ ==========
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player)) return;
        if (!getConfig().getBoolean("block.movement", true)) return;

        if (event.getFrom().getX() != event.getTo().getX() ||
                event.getFrom().getY() != event.getTo().getY() ||
                event.getFrom().getZ() != event.getTo().getZ()) {
            Location from = event.getFrom().clone();
            from.setYaw(event.getTo().getYaw());
            from.setPitch(event.getTo().getPitch());
            event.setTo(from);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isAuthenticated(event.getPlayer()) && getConfig().getBoolean("block.chat", true)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg(event.getPlayer(), "chat-denied"));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        boolean needsTgLink = getConfig().getBoolean("require-telegram-link", false)
                && getTelegramChatId(player.getUniqueId()) == null;

        if (isAuthenticated(player) && !needsTgLink) return;

        String message = event.getMessage().toLowerCase(Locale.ROOT);
        boolean allowed = message.equals("/meowlogin") || message.startsWith("/meowlogin ") ||
                message.equals("/ml") || message.startsWith("/ml ") ||
                message.equals("/l") || message.startsWith("/l ") ||
                message.equals("/login") || message.startsWith("/login ") ||
                message.equals("/reg") || message.startsWith("/reg ") ||
                message.equals("/register") || message.startsWith("/register ") ||
                message.equals("/resetpassword") ||
                message.equals("/link") ||
                message.equals("/unlink");
        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage(msg(player, "command-denied"));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player)) return;
        if (!getConfig().getBoolean("block.interact", true)) return;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK ||
                event.getAction() == Action.RIGHT_CLICK_AIR ||
                event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!isAuthenticated(event.getPlayer()) && getConfig().getBoolean("block.interact-entity", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isAuthenticated(event.getPlayer()) && getConfig().getBoolean("block.block-break", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isAuthenticated(event.getPlayer()) && getConfig().getBoolean("block.block-place", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!isAuthenticated(event.getPlayer()) && getConfig().getBoolean("block.item-drop", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !isAuthenticated(player) && getConfig().getBoolean("block.item-pickup", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!getConfig().getBoolean("block.damage", true)) return;
        if (event.getDamager() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
        if (event.getEntity() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (!getConfig().getBoolean("block.mob-target", true)) return;
        if (event.getTarget() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player)) return;
        if (!getConfig().getBoolean("block.gamemode-change", true)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (isAuthenticated(event.getPlayer())) return;
        if (!getConfig().getBoolean("block.flight", true)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamageAny(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isAuthenticated(player)) return;
        if (!getConfig().getBoolean("block.damage", true)) return;
        event.setCancelled(true);
    }
}