package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.MainConfig;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class RedisStateCache implements AutoCloseable {
    private final JedisPooled jedis;
    private final String prefix;
    private final int ttlSeconds;

    RedisStateCache(MainConfig.RedisConfig config, String namespace) {
        jedis = new JedisPooled(URI.create(config.uri()));
        prefix = config.keyPrefix() + "cache:" + namespace + ':';
        ttlSeconds = config.cacheTtlSeconds();
    }

    void ping() {
        jedis.ping();
    }

    Optional<PlayerNoticeState> get(UUID uniqueId, String streamId) {
        List<String> values = jedis.mget(rulesKey(uniqueId), changelogKey(uniqueId, streamId));
        if (values.size() != 2 || values.get(0) == null || values.get(1) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PlayerNoticeState("1".equals(values.get(0)), Long.parseLong(values.get(1))));
        } catch (NumberFormatException exception) {
            delete(uniqueId, streamId);
            return Optional.empty();
        }
    }

    void put(UUID uniqueId, String streamId, PlayerNoticeState state) {
        if (state.rulesAccepted()) {
            jedis.setex(rulesKey(uniqueId), ttlSeconds, "1");
        } else {
            jedis.del(rulesKey(uniqueId));
        }
        jedis.setex(changelogKey(uniqueId, streamId), ttlSeconds, Long.toString(state.lastSeenRevision()));
    }

    void setRulesAccepted(UUID uniqueId) {
        jedis.setex(rulesKey(uniqueId), ttlSeconds, "1");
    }

    void setSeenRevision(UUID uniqueId, String streamId, long revision) {
        String key = changelogKey(uniqueId, streamId);
        String script = "local v=redis.call('GET',KEYS[1]); "
                + "if (not v) or (tonumber(v)<tonumber(ARGV[1])) then "
                + "redis.call('SETEX',KEYS[1],ARGV[2],ARGV[1]); return 1 else return 0 end";
        jedis.eval(script, List.of(key), List.of(Long.toString(revision), Integer.toString(ttlSeconds)));
    }

    private void delete(UUID uniqueId, String streamId) {
        jedis.del(rulesKey(uniqueId), changelogKey(uniqueId, streamId));
    }

    private String rulesKey(UUID uniqueId) {
        return prefix + "rules:" + uniqueId;
    }

    private String changelogKey(UUID uniqueId, String streamId) {
        return prefix + "changelog:" + streamId + ':' + uniqueId;
    }

    @Override
    public void close() {
        jedis.close();
    }
}
