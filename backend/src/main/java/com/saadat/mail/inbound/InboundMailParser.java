package com.saadat.mail.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient reader of an inbound-mail webhook payload whose exact shape is not documented by the mail host.
 * Keys are compared case-insensitively with {@code _} and {@code -} ignored ({@code envelope_from} =
 * {@code envelopeFrom} = {@code Envelope-From}).
 *
 * <ul>
 *   <li>sender: breadth-first search for a key named {@code from} (then {@code envelope_from}, then
 *       {@code sender}); the first e-mail address found anywhere under that key wins — so {@code "from": "a@b.c"},
 *       {@code from.address}, {@code from.email}, {@code from[0].address}, {@code data.from…},
 *       {@code message.from…} and {@code envelope.from} all work, as does {@code "Name <a@b.c>"}</li>
 *   <li>sender name: the {@code name} next to that address ({@code from.name}, {@code from[0].name}) or the
 *       display part of {@code "Name <a@b.c>"}</li>
 *   <li>subject / message id: the first text under a key {@code subject} / {@code message_id}</li>
 *   <li>received time: the first value under {@code received_at}, then {@code date}, then {@code timestamp} that
 *       parses as ISO-8601, RFC 1123 (e-mail {@code Date:} header) or epoch seconds/milliseconds; else null</li>
 *   <li>body: {@link #body} — the plain text the person wrote, when the payload carries it (else null)</li>
 *   <li>headers: {@link #headerValues} finds a header given as a key ({@code "Auto-Submitted": "auto-replied"}),
 *       as {@code {name|key, value}} pairs, or inside a raw header block ({@code "headers": "A: b\r\n…"})</li>
 * </ul>
 */
public final class InboundMailParser {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final List<String> SENDER_KEYS = List.of("from", "envelopefrom", "sender");
    private static final List<String> RECEIVED_KEYS = List.of("receivedat", "date", "timestamp");
    private static final Pattern DISPLAY_NAME = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<");
    private static final int NAME_MAX = 200;
    /** Epoch values below this are seconds, above are milliseconds (1e11 s ≈ year 5138). */
    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;
    private static final Set<String> NAME_KEYS = Set.of("name", "key", "header");
    private static final int MAX_NODES = 5000;
    /** Keys (normalized) that hold the plain text of the message, most specific first. */
    private static final List<String> BODY_TEXT_KEYS = List.of("textbody", "bodytext", "textplain", "bodyplain",
            "plaintext", "plain", "text", "body", "content");
    /** Keys (normalized) that hold the HTML of the message. */
    private static final List<String> BODY_HTML_KEYS = List.of("htmlbody", "bodyhtml", "texthtml", "html");
    /** Last resort: a short preview of the message. */
    private static final List<String> BODY_PREVIEW_KEYS = List.of("snippet", "preview", "intro");
    /** Parts of the payload that never hold the message text (a "text" there is a name or a header). */
    private static final Set<String> BODY_SKIP_KEYS = Set.of("from", "to", "cc", "bcc", "sender", "replyto",
            "envelope", "envelopefrom", "envelopeto", "headers", "header", "attachments", "attachment", "subject",
            "raw", "source", "eml");

    private InboundMailParser() {
    }

    /**
     * What the auto-reply and the support ticket need from the payload. {@code senderName}, {@code receivedAt} and
     * {@code body} (plain text the person wrote, already cleaned by {@link MailBodyText#clean}) may be null.
     */
    public record InboundMessage(String sender, String subject, String messageId, String senderName,
                                 Instant receivedAt, String body) {

        /** Without a body (the text is fetched later over IMAP). */
        public InboundMessage(String sender, String subject, String messageId, String senderName,
                              Instant receivedAt) {
            this(sender, subject, messageId, senderName, receivedAt, null);
        }
    }

    public static InboundMessage parse(JsonNode root) {
        String sender = null;
        String senderName = null;
        for (String key : SENDER_KEYS) {
            Optional<JsonNode> node = firstUnderKey(root, key);
            if (node.isPresent()) {
                sender = firstEmail(node.get()).orElse(null);
                if (sender != null) {
                    senderName = displayName(node.get());
                    break;
                }
            }
        }
        String subject = firstUnderKey(root, "subject").flatMap(InboundMailParser::firstText).orElse(null);
        String messageId = firstUnderKey(root, "messageid").flatMap(InboundMailParser::firstText).orElse(null);
        Instant receivedAt = null;
        for (String key : RECEIVED_KEYS) {
            Optional<JsonNode> node = firstUnderKey(root, key);
            if (node.isPresent()) {
                receivedAt = parseInstant(node.get());
                if (receivedAt != null) {
                    break;
                }
            }
        }
        return new InboundMessage(sender, subject, messageId, senderName, receivedAt, body(root));
    }

    /**
     * The plain text the person wrote, when the payload carries it: the first text under a plain-text key
     * ({@code text}, {@code text_body}, {@code plain}, {@code body} as text, {@code body.text}, {@code content.text}
     * …), else an HTML key ({@code html}, {@code html_body}, {@code body.html} …) converted to text, else
     * {@code snippet}/{@code preview}. The sender/recipient, header and attachment parts of the payload are not
     * searched; base64 blobs and values over {@link MailBodyText#MAX_INPUT_CHARS} are ignored. Cleaned with
     * {@link MailBodyText#clean}; null when there is none.
     */
    public static String body(JsonNode root) {
        for (String key : BODY_TEXT_KEYS) {
            String value = firstBodyText(root, key);
            if (value != null) {
                String text = MailBodyText.clean(MailBodyText.looksLikeHtml(value)
                        ? MailBodyText.htmlToText(value) : value);
                if (text != null) {
                    return text;
                }
            }
        }
        for (String key : BODY_HTML_KEYS) {
            String value = firstBodyText(root, key);
            if (value != null) {
                String text = MailBodyText.clean(MailBodyText.htmlToText(value));
                if (text != null) {
                    return text;
                }
            }
        }
        for (String key : BODY_PREVIEW_KEYS) {
            String value = firstBodyText(root, key);
            if (value != null) {
                String text = MailBodyText.clean(value);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Breadth-first: the shallowest usable text value under {@code key}, skipping the subtrees of
     * {@link #BODY_SKIP_KEYS}.
     */
    private static String firstBodyText(JsonNode root, String key) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String name = normalize(field.getKey());
                    JsonNode value = field.getValue();
                    if (value == null || BODY_SKIP_KEYS.contains(name)) {
                        continue;
                    }
                    if (name.equals(key) && value.isTextual()) {
                        String text = value.asText();
                        if (!text.isBlank() && text.length() <= MailBodyText.MAX_INPUT_CHARS
                                && !MailBodyText.looksLikeBase64(text)) {
                            return text;
                        }
                    }
                    if (value.isContainerNode()) {
                        queue.add(value);
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return null;
    }

    /**
     * Display name of a sender node: {@code {"name": "A"}}, {@code [{"name": "A", ...}]} or the text
     * {@code "A" <a@b.c>}; null when there is none or it is itself an e-mail address.
     */
    static String displayName(JsonNode node) {
        String name = null;
        JsonNode n = node;
        if (n.isArray() && !n.isEmpty()) {
            n = n.get(0);
        }
        if (n.isObject()) {
            JsonNode value = n.get("name");
            if (value == null) {
                value = n.get("display_name");
            }
            if (value != null && value.isTextual()) {
                name = value.asText();
            } else {
                for (String key : List.of("address", "email", "value", "text")) {
                    JsonNode text = n.get(key);
                    if (text != null && text.isTextual()) {
                        name = nameFromText(text.asText());
                        if (name != null) {
                            break;
                        }
                    }
                }
            }
        } else if (n.isTextual()) {
            name = nameFromText(n.asText());
        }
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty() || EMAIL.matcher(cleaned).matches()) {
            return null;
        }
        return cleaned.length() > NAME_MAX ? cleaned.substring(0, NAME_MAX) : cleaned;
    }

    private static String nameFromText(String text) {
        Matcher m = DISPLAY_NAME.matcher(text);
        if (m.find()) {
            String name = m.group(1).trim();
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    /** ISO-8601 instant/offset, RFC 1123 or epoch seconds/milliseconds; null when it does not parse. */
    static Instant parseInstant(JsonNode node) {
        if (node.isNumber()) {
            return fromEpoch(node.asLong());
        }
        if (!node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit) && text.length() <= 15) {
            return fromEpoch(Long.parseLong(text));
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            // "Fri, 3 Oct 2026 10:15:00 +0300 (AST)" → drop the trailing comment
            String rfc = text.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
            return ZonedDateTime.parse(rfc, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Instant fromEpoch(long value) {
        if (value <= 0) {
            return null;
        }
        return value < EPOCH_MILLIS_THRESHOLD ? Instant.ofEpochSecond(value) : Instant.ofEpochMilli(value);
    }

    /** Every value of the header {@code name} (e.g. "Auto-Submitted") found in the payload. */
    public static List<String> headerValues(JsonNode root, String name) {
        String wanted = normalize(name);
        Pattern line = Pattern.compile("(?im)^\\s*" + Pattern.quote(name) + "\\s*:\\s*(.+?)\\s*$");
        List<String> out = new ArrayList<>();
        Deque<JsonNode> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                String pairName = null;
                for (String nk : NAME_KEYS) {
                    JsonNode n = node.get(nk);
                    if (n != null && n.isTextual()) {
                        pairName = n.asText();
                        break;
                    }
                }
                if (pairName != null && normalize(pairName).equals(wanted)) {
                    JsonNode value = node.get("value");
                    if (value != null && !value.isContainerNode()) {
                        out.add(value.asText());
                    }
                }
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = normalize(field.getKey());
                    JsonNode value = field.getValue();
                    if (key.equals(wanted)) {
                        if (value.isArray()) {
                            value.forEach(v -> {
                                if (!v.isContainerNode()) {
                                    out.add(v.asText());
                                }
                            });
                        } else if (!value.isContainerNode() && !value.isNull()) {
                            out.add(value.asText());
                        }
                    } else if (value.isTextual() && (key.contains("header") || key.equals("raw"))) {
                        Matcher m = line.matcher(value.asText());
                        while (m.find()) {
                            out.add(m.group(1));
                        }
                    }
                    if (value.isContainerNode()) {
                        queue.add(value);
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    /** Breadth-first: the value of the shallowest field whose normalized key equals {@code key}. */
    static Optional<JsonNode> firstUnderKey(JsonNode root, String key) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode value = field.getValue();
                    if (normalize(field.getKey()).equals(key) && value != null && !value.isNull()) {
                        return Optional.of(value);
                    }
                    if (value != null && value.isContainerNode()) {
                        queue.add(value);
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return Optional.empty();
    }

    /** First e-mail address in any text under {@code node} (lower-cased). */
    static Optional<String> firstEmail(JsonNode node) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(node);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            JsonNode n = queue.poll();
            if (n.isTextual()) {
                Matcher m = EMAIL.matcher(n.asText());
                if (m.find()) {
                    return Optional.of(m.group().toLowerCase(Locale.ROOT));
                }
            } else if (n.isContainerNode()) {
                n.forEach(queue::add);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstText(JsonNode node) {
        if (node.isTextual() || node.isNumber()) {
            String text = node.asText().trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                Optional<String> found = firstText(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    static String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
