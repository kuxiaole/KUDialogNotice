package dev.kuxiaole.kudialognotice.config;

public record MainConfig(
        String serverId,
        long displayDelayTicks,
        MariaDbConfig mariaDb,
        RedisConfig redis,
        ClientConfig clients
) {
    public record MariaDbConfig(
            String jdbcUrl,
            String username,
            String password,
            String tablePrefix,
            int maximumPoolSize,
            long connectionTimeoutMillis,
            long socketTimeoutMillis
    ) {
    }

    public record RedisConfig(
            boolean enabled,
            String uri,
            String keyPrefix,
            int cacheTtlSeconds
    ) {
    }

    public record ClientConfig(
            int nativeDialogMinProtocol,
            boolean fallbackBookEnabled
    ) {
    }
}
