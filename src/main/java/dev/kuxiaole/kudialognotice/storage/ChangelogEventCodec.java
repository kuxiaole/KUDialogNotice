package dev.kuxiaole.kudialognotice.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Encodes changelog invalidation events without putting the YAML body on Redis.
 * The wire format is intentionally boring and bounded so malformed messages are
 * cheap to reject and cannot become a YAML/JSON parsing attack surface:
 * {@code v1|namespace|notice|revision|sha256|server|nodeUuid|eventUuid}.
 */
public final class ChangelogEventCodec {
    private static final String VERSION = "1";
    private static final int MAX_BYTES = 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ChangelogEventCodec() {
    }

    public static String encode(ChangelogEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        validate(event);
        String value = String.join("|",
                VERSION,
                event.namespace(),
                event.noticeId(),
                Long.toString(event.revision()),
                event.sha256().toLowerCase(Locale.ROOT),
                event.sourceServerId(),
                event.sourceNodeId().toString(),
                event.eventId().toString());
        ensureSize(value);
        return value;
    }

    /** Parse an event, returning empty for any malformed or oversized input. */
    public static Optional<ChangelogEvent> decode(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            ensureSize(value);
            String[] fields = value.split("\\|", -1);
            if (fields.length != 8 || !VERSION.equals(fields[0])) {
                return Optional.empty();
            }
            long revision = Long.parseLong(fields[3]);
            if (revision < 0L || !SAFE_ID.matcher(fields[1]).matches()
                    || !SAFE_ID.matcher(fields[2]).matches()
                    || !SERVER_ID.matcher(fields[5]).matches()
                    || !SHA256.matcher(fields[4]).matches()) {
                return Optional.empty();
            }
            ChangelogEvent event = new ChangelogEvent(
                    fields[1], fields[2], revision, fields[4], fields[5],
                    UUID.fromString(fields[6]), UUID.fromString(fields[7]));
            validate(event);
            return Optional.of(event);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static void validate(ChangelogEvent event) {
        if (!SAFE_ID.matcher(event.namespace()).matches()
                || !SAFE_ID.matcher(event.noticeId()).matches()
                || !SERVER_ID.matcher(event.sourceServerId()).matches()
                || !SHA256.matcher(event.sha256()).matches()) {
            throw new IllegalArgumentException("invalid changelog event field");
        }
    }

    private static void ensureSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("changelog event is too large");
        }
    }
}
