package dev.kuxiaole.kudialognotice.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ConfigurationLoader {
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final List<String> RESOURCE_FILES = List.of(
            "config.yml", "rules.yml", "changelog.yml", "messages.yml"
    );
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();
    private static final TagResolver MINI_MESSAGE_PLACEHOLDERS = TagResolver.resolver(
            Placeholder.component("player", Component.text("player")),
            Placeholder.component("server", Component.text("server")),
            Placeholder.component("version", Component.text("version")),
            Placeholder.component("revision", Component.text("1")),
            Placeholder.component("error", Component.text("error"))
    );

    private final JavaPlugin plugin;

    public ConfigurationLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void installDefaults() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Cannot create plugin data directory: " + plugin.getDataFolder());
        }
        for (String resource : RESOURCE_FILES) {
            File destination = new File(plugin.getDataFolder(), resource);
            if (!destination.exists()) {
                plugin.saveResource(resource, false);
            }
        }
    }

    public PluginConfiguration load() throws ConfigurationException {
        try {
            MainConfig main = loadMain(loadYaml("config.yml"));
            RulesConfig rules = loadRules(loadYaml("rules.yml"));
            ChangelogConfig changelog = loadChangelog(loadYaml("changelog.yml"));
            MessagesConfig messages = loadMessages(loadYaml("messages.yml"));
            return new PluginConfiguration(main, rules, changelog, messages);
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigurationException("Invalid configuration: " + exception.getMessage(), exception);
        }
    }

    private YamlConfiguration loadYaml(String name) throws ConfigurationException {
        File file = new File(plugin.getDataFolder(), name);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (Exception exception) {
            throw new ConfigurationException("Cannot load " + name + ": " + exception.getMessage(), exception);
        }
    }

    private MainConfig loadMain(YamlConfiguration yaml) throws ConfigurationException {
        String serverId = requiredString(yaml, "server-id").toLowerCase(Locale.ROOT);
        require(SERVER_ID.matcher(serverId).matches(),
                "config.yml: server-id must match " + SERVER_ID.pattern());

        long delay = yaml.getLong("display-delay-ticks", 30L);
        require(delay >= 1L && delay <= 20L * 60L,
                "config.yml: display-delay-ticks must be between 1 and 1200");

        String tablePrefix = requiredString(yaml, "storage.mariadb.table-prefix");
        require(TABLE_PREFIX.matcher(tablePrefix).matches(),
                "config.yml: storage.mariadb.table-prefix may contain only letters, digits and underscores");

        MainConfig.MariaDbConfig mariaDb = new MainConfig.MariaDbConfig(
                requiredString(yaml, "storage.mariadb.jdbc-url"),
                requiredString(yaml, "storage.mariadb.username"),
                Objects.requireNonNullElse(yaml.getString("storage.mariadb.password"), ""),
                tablePrefix,
                boundedInt(yaml, "storage.mariadb.maximum-pool-size", 6, 1, 32),
                boundedLong(yaml, "storage.mariadb.connection-timeout-ms", 5000L, 1000L, 60_000L),
                boundedLong(yaml, "storage.mariadb.socket-timeout-ms", 15_000L, 1000L, 120_000L)
        );
        require(mariaDb.jdbcUrl().startsWith("jdbc:mariadb:"),
                "config.yml: storage.mariadb.jdbc-url must start with jdbc:mariadb:");

        boolean redisEnabled = yaml.getBoolean("storage.redis.enabled", true);
        MainConfig.RedisConfig redis = new MainConfig.RedisConfig(
                redisEnabled,
                requiredString(yaml, "storage.redis.uri"),
                requiredString(yaml, "storage.redis.key-prefix"),
                boundedInt(yaml, "storage.redis.cache-ttl-seconds", 300, 5, 86_400)
        );
        if (redisEnabled) {
            validateRedisUri(redis.uri());
        }

        MainConfig.ClientConfig clients = new MainConfig.ClientConfig(
                boundedInt(yaml, "clients.native-dialog-min-protocol", 771, 1, Integer.MAX_VALUE),
                yaml.getBoolean("clients.fallback-book-enabled", true)
        );
        return new MainConfig(serverId, delay, mariaDb, redis, clients);
    }

    private RulesConfig loadRules(YamlConfiguration yaml) throws ConfigurationException {
        List<String> body = miniMessageStringList(yaml, "body");
        return new RulesConfig(
                yaml.getBoolean("enabled", true),
                miniMessageString(yaml, "title"),
                body,
                miniMessageString(yaml, "read-checkbox"),
                miniMessageString(yaml, "locked-message"),
                new RulesConfig.Button(
                        miniMessageString(yaml, "buttons.accept.text"),
                        miniMessageString(yaml, "buttons.accept.tooltip")
                ),
                new RulesConfig.Button(
                        miniMessageString(yaml, "buttons.reject.text"),
                        miniMessageString(yaml, "buttons.reject.tooltip")
                ),
                new RulesConfig.RejectionConfirmation(
                        miniMessageString(yaml, "reject-confirmation.title"),
                        miniMessageString(yaml, "reject-confirmation.body"),
                        miniMessageString(yaml, "reject-confirmation.return-button"),
                        miniMessageString(yaml, "reject-confirmation.reject-button")
                )
        );
    }

    private ChangelogConfig loadChangelog(YamlConfiguration yaml) throws ConfigurationException {
        long revision = yaml.getLong("revision", 0L);
        boolean enabled = yaml.getBoolean("enabled", true);
        if (enabled) {
            require(revision >= 1L, "changelog.yml: revision must be at least 1 when enabled");
        }
        return new ChangelogConfig(
                enabled,
                revision,
                requiredString(yaml, "version-label"),
                miniMessageString(yaml, "title"),
                miniMessageStringList(yaml, "body"),
                miniMessageString(yaml, "acknowledge-button"),
                miniMessageString(yaml, "acknowledge-tooltip")
        );
    }

    private MessagesConfig loadMessages(YamlConfiguration yaml) throws ConfigurationException {
        return new MessagesConfig(
                miniMessageString(yaml, "prefix"),
                miniMessageString(yaml, "database-unavailable"),
                miniMessageString(yaml, "unsupported-client"),
                miniMessageString(yaml, "rules-save-failed"),
                miniMessageString(yaml, "changelog-save-failed"),
                miniMessageString(yaml, "rejected-kick"),
                miniMessageString(yaml, "reload-success"),
                miniMessageString(yaml, "reload-failed"),
                miniMessageString(yaml, "no-permission"),
                miniMessageString(yaml, "player-not-found"),
                miniMessageString(yaml, "invalid-command")
        );
    }

    private static String miniMessageString(YamlConfiguration yaml, String path)
            throws ConfigurationException {
        String value = requiredString(yaml, path);
        validateMiniMessage(yaml, path, value);
        return value;
    }

    private static List<String> miniMessageStringList(YamlConfiguration yaml, String path)
            throws ConfigurationException {
        List<String> values = requiredStringList(yaml, path);
        require(values.size() <= 32,
                yaml.getName() + ": " + path + " may contain at most 32 entries");
        for (int index = 0; index < values.size(); index++) {
            validateMiniMessage(yaml, path + '[' + index + ']', values.get(index));
        }
        return values;
    }

    static void validateMiniMessage(YamlConfiguration yaml, String path, String value)
            throws ConfigurationException {
        try {
            STRICT_MINI_MESSAGE.deserialize(value, MINI_MESSAGE_PLACEHOLDERS);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                    yaml.getName() + ": invalid MiniMessage at " + path + ": " + exception.getMessage(),
                    exception);
        }
    }

    private static String requiredString(YamlConfiguration yaml, String path) throws ConfigurationException {
        String value = yaml.getString(path);
        require(value != null && !value.isBlank(), yaml.getName() + ": missing non-empty " + path);
        return value;
    }

    private static List<String> requiredStringList(YamlConfiguration yaml, String path) throws ConfigurationException {
        List<String> values = yaml.getStringList(path);
        require(!values.isEmpty() && values.stream().noneMatch(String::isBlank),
                yaml.getName() + ": " + path + " must contain at least one non-empty entry");
        return List.copyOf(values);
    }

    private static int boundedInt(YamlConfiguration yaml, String path, int fallback, int min, int max)
            throws ConfigurationException {
        int value = yaml.getInt(path, fallback);
        require(value >= min && value <= max,
                yaml.getName() + ": " + path + " must be between " + min + " and " + max);
        return value;
    }

    private static long boundedLong(YamlConfiguration yaml, String path, long fallback, long min, long max)
            throws ConfigurationException {
        long value = yaml.getLong(path, fallback);
        require(value >= min && value <= max,
                yaml.getName() + ": " + path + " must be between " + min + " and " + max);
        return value;
    }

    private static void validateRedisUri(String value) throws ConfigurationException {
        try {
            URI uri = URI.create(value);
            require(("redis".equalsIgnoreCase(uri.getScheme()) || "rediss".equalsIgnoreCase(uri.getScheme()))
                            && uri.getHost() != null && !uri.getHost().isBlank(),
                    "config.yml: storage.redis.uri must be a valid redis:// or rediss:// URI with a host");
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("config.yml: invalid storage.redis.uri: " + exception.getMessage(),
                    exception);
        }
    }

    private static void require(boolean condition, String message) throws ConfigurationException {
        if (!condition) {
            throw new ConfigurationException(message);
        }
    }
}
