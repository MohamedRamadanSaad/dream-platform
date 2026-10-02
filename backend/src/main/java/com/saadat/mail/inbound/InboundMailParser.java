package com.saadat.mail.inbound;

import com.fasterxml.jackson.databind.JsonNode;
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
 *   <li>subject / message id: the first text under a key {@code subject} / {@code message_id}</li>
 *   <li>headers: {@link #headerValues} finds a header given as a key ({@code "Auto-Submitted": "auto-replied"}),
 *       as {@code {name|key, value}} pairs, or inside a raw header block ({@code "headers": "A: b\r\n…"})</li>
 * </ul>
 */
public final class InboundMailParser {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final List<String> SENDER_KEYS = List.of("from", "envelopefrom", "sender");
    private static final Set<String> NAME_KEYS = Set.of("name", "key", "header");
    private static final int MAX_NODES = 5000;

    private InboundMailParser() {
    }

    /** What the auto-reply needs from the payload. */
    public record InboundMessage(String sender, String subject, String messageId) {
    }

    public static InboundMessage parse(JsonNode root) {
        String sender = null;
        for (String key : SENDER_KEYS) {
            Optional<JsonNode> node = firstUnderKey(root, key);
            if (node.isPresent()) {
                sender = firstEmail(node.get()).orElse(null);
                if (sender != null) {
                    break;
                }
            }
        }
        String subject = firstUnderKey(root, "subject").flatMap(InboundMailParser::firstText).orElse(null);
        String messageId = firstUnderKey(root, "messageid").flatMap(InboundMailParser::firstText).orElse(null);
        return new InboundMessage(sender, subject, messageId);
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
