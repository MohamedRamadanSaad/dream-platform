package com.saadat.common.geo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MaxMind DB (format 2.0) writer for tests, so GeoIP fixtures are built from readable code instead of
 * committed binaries. Writes what DB-IP's "IP to Country Lite" file looks like: an IPv6 search tree with the IPv4
 * networks under {@code ::/96}, 24-bit records, and the data types a country record uses (map, UTF-8 string,
 * uint16/uint32/uint64, boolean, array). Networks must not overlap.
 */
final class MmdbTestWriter {

    private static final byte[] METADATA_MARKER = {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'};
    private static final int IPV4_PREFIX_BITS = 96;
    private static final int RECORD_SIZE_BITS = 24;
    private static final int DATA_SECTION_SEPARATOR = 16;

    private static final int TYPE_STRING = 2;
    private static final int TYPE_UINT16 = 5;
    private static final int TYPE_UINT32 = 6;
    private static final int TYPE_MAP = 7;
    private static final int TYPE_UINT64 = 9;
    private static final int TYPE_ARRAY = 11;
    private static final int TYPE_BOOLEAN = 14;

    private final String databaseType;
    private final Node root = new Node();

    MmdbTestWriter(String databaseType) {
        this.databaseType = databaseType;
    }

    /** A DB-IP-like country record: continent + country with ISO code, GeoNames id and English names. */
    static Map<String, Object> country(String isoCode, String name, long geonameId, String continentCode) {
        Map<String, Object> continent = new LinkedHashMap<>();
        continent.put("code", continentCode);
        continent.put("geoname_id", new Uint32(6_255_146L));
        continent.put("names", Map.of("en", continentCode));
        Map<String, Object> country = new LinkedHashMap<>();
        country.put("geoname_id", new Uint32(geonameId));
        country.put("is_in_european_union", Boolean.FALSE);
        country.put("iso_code", isoCode);
        country.put("names", Map.of("en", name));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("continent", continent);
        record.put("country", country);
        return record;
    }

    /** Adds a network such as {@code 203.0.113.0/24} or {@code 2001:db8::/32} with its record. */
    MmdbTestWriter add(String cidr, Map<String, Object> record) throws IOException {
        int slash = cidr.indexOf('/');
        byte[] address = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        byte[] bits = new byte[16];
        if (address.length == 4) {
            System.arraycopy(address, 0, bits, 12, 4);
            prefix += IPV4_PREFIX_BITS;
        } else {
            bits = address;
        }
        Node node = root;
        for (int i = 0; i < prefix - 1; i++) {
            int bit = bit(bits, i);
            Object child = node.children[bit];
            if (child == null) {
                child = new Node();
                node.children[bit] = child;
            }
            if (!(child instanceof Node next)) {
                throw new IllegalArgumentException("Overlapping network " + cidr);
            }
            node = next;
        }
        int last = bit(bits, prefix - 1);
        if (node.children[last] != null) {
            throw new IllegalArgumentException("Overlapping network " + cidr);
        }
        node.children[last] = new Data(record);
        return this;
    }

    void write(Path file) throws IOException {
        Files.write(file, build());
    }

    byte[] build() {
        List<Node> nodes = new ArrayList<>();
        Map<Node, Integer> numbers = new IdentityHashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            numbers.put(node, nodes.size());
            nodes.add(node);
            for (Object child : node.children) {
                if (child instanceof Node next) {
                    queue.add(next);
                }
            }
        }
        long nodeCount = nodes.size();

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Map<Data, Integer> offsets = new IdentityHashMap<>();
        for (Node node : nodes) {
            for (Object child : node.children) {
                if (child instanceof Data d && !offsets.containsKey(d)) {
                    offsets.put(d, data.size());
                    encode(data, d.fields());
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Node node : nodes) {
            for (Object child : node.children) {
                long value;
                if (child == null) {
                    value = nodeCount;                       // "no data"
                } else if (child instanceof Node next) {
                    value = numbers.get(next);
                } else {
                    value = nodeCount + DATA_SECTION_SEPARATOR + offsets.get((Data) child);
                }
                out.write((int) (value >>> 16) & 0xFF);
                out.write((int) (value >>> 8) & 0xFF);
                out.write((int) value & 0xFF);
            }
        }
        out.writeBytes(new byte[DATA_SECTION_SEPARATOR]);
        out.writeBytes(data.toByteArray());
        out.writeBytes(METADATA_MARKER);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("binary_format_major_version", new Uint16(2));
        metadata.put("binary_format_minor_version", new Uint16(0));
        metadata.put("build_epoch", new Uint64(1_790_000_000L));
        metadata.put("database_type", databaseType);
        metadata.put("description", Map.of("en", "test fixture"));
        metadata.put("ip_version", new Uint16(6));
        metadata.put("languages", List.of("en"));
        metadata.put("node_count", new Uint32(nodeCount));
        metadata.put("record_size", new Uint16(RECORD_SIZE_BITS));
        encode(out, metadata);
        return out.toByteArray();
    }

    private static int bit(byte[] address, int index) {
        return (address[index / 8] >> (7 - index % 8)) & 1;
    }

    private static void encode(ByteArrayOutputStream out, Object value) {
        if (value instanceof String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            control(out, TYPE_STRING, bytes.length);
            out.writeBytes(bytes);
        } else if (value instanceof Map<?, ?> map) {
            control(out, TYPE_MAP, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                encode(out, (String) e.getKey());
                encode(out, e.getValue());
            }
        } else if (value instanceof List<?> list) {
            control(out, TYPE_ARRAY, list.size());
            for (Object item : list) {
                encode(out, item);
            }
        } else if (value instanceof Boolean b) {
            control(out, TYPE_BOOLEAN, b ? 1 : 0);   // the value is the size field, no payload
        } else if (value instanceof Uint16 u) {
            unsigned(out, TYPE_UINT16, u.value());
        } else if (value instanceof Uint32 u) {
            unsigned(out, TYPE_UINT32, u.value());
        } else if (value instanceof Uint64 u) {
            unsigned(out, TYPE_UINT64, u.value());
        } else {
            throw new IllegalArgumentException("Unsupported type " + value.getClass());
        }
    }

    /** Big-endian, leading zero bytes dropped (the size is the number of bytes). */
    private static void unsigned(ByteArrayOutputStream out, int type, long value) {
        int size = 0;
        for (long v = value; v != 0; v >>>= 8) {
            size++;
        }
        control(out, type, size);
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    /** Control byte, then the extended-type byte (types above 7), then the size extension bytes. */
    private static void control(ByteArrayOutputStream out, int type, int size) {
        int typeBits = type <= 7 ? type << 5 : 0;
        if (size < 29) {
            out.write(typeBits | size);
            extendedType(out, type);
        } else if (size < 29 + 256) {
            out.write(typeBits | 29);
            extendedType(out, type);
            out.write(size - 29);
        } else if (size < 285 + 65_536) {
            out.write(typeBits | 30);
            extendedType(out, type);
            int rest = size - 285;
            out.write(rest >>> 8);
            out.write(rest & 0xFF);
        } else {
            throw new IllegalArgumentException("Field too large for a test fixture: " + size);
        }
    }

    private static void extendedType(ByteArrayOutputStream out, int type) {
        if (type > 7) {
            out.write(type - 7);
        }
    }

    record Uint16(int value) {
    }

    record Uint32(long value) {
    }

    record Uint64(long value) {
    }

    private record Data(Map<String, Object> fields) {
    }

    private static final class Node {
        private final Object[] children = new Object[2];
    }
}
