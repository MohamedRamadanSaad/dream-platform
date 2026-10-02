package com.saadat.common.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The visitor's public IP address for geolocation, taken only from values the client cannot choose.
 *
 * <p>Caddy is the only ingress and replaces any client-supplied {@code X-Forwarded-For} with the address it saw.
 * So when the TCP peer is the proxy (a loopback, private or link-local address: the compose network), the client
 * is the LAST {@code X-Forwarded-For} hop; any other peer is the client itself. Both are read from the raw container
 * request: Spring's {@code ForwardedHeaderFilter} ({@code server.forward-headers-strategy=framework}) hides that
 * header and rewrites {@code getRemoteAddr()} from the first hop or from a client-supplied {@code Forwarded} header.
 *
 * <p>Private, loopback, link-local and malformed addresses give empty (nothing to look up). Only IP literals are
 * parsed, so a header value can never trigger a DNS lookup.
 */
public final class ProxyClientIp {

    private static final int MAX_LENGTH = 64;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    /** Hex digits, colons and dots (embedded IPv4), starting with a hex digit or a colon; no zone id. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");
    /** {@code 1.2.3.4:5678} — a proxy hop written with its port. */
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("([0-9.]+):\\d{1,5}");
    /** {@code [2001:db8::1]} or {@code [2001:db8::1]:5678}. */
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]]+)](?::\\d{1,5})?");

    private ProxyClientIp() {
    }

    /** The client's public address, or empty when it is unknown, malformed or not public. */
    public static Optional<InetAddress> publicAddress(HttpServletRequest request) {
        HttpServletRequest raw = unwrap(request);
        InetAddress peer = parse(raw.getRemoteAddr()).orElse(null);
        if (peer == null) {
            return Optional.empty();
        }
        InetAddress client = peer;
        if (isInternal(peer)) {
            String lastHop = lastForwardedHop(raw.getHeaders(ClientIp.HEADER_X_FORWARDED_FOR));
            if (lastHop != null) {
                client = parse(lastHop).orElse(null);
            }
        }
        return client == null || isInternal(client) ? Optional.empty() : Optional.of(client);
    }

    /** The request as the servlet container received it (below every filter's wrapper). */
    static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequest http ? http : request;
    }

    /** Last non-blank entry over every X-Forwarded-For header line, or null. */
    static String lastForwardedHop(Enumeration<String> headers) {
        String last = null;
        while (headers != null && headers.hasMoreElements()) {
            String line = headers.nextElement();
            if (line == null) {
                continue;
            }
            for (String part : line.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    last = hop;
                }
            }
        }
        return last;
    }

    /** Parses an IP literal (optionally bracketed or with a port); never resolves host names. */
    static Optional<InetAddress> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher bracketed = BRACKETED.matcher(text);
        if (bracketed.matches()) {
            text = bracketed.group(1);
        } else {
            Matcher withPort = IPV4_WITH_PORT.matcher(text);
            if (withPort.matches()) {
                text = withPort.group(1);
            }
        }
        Matcher v4 = IPV4.matcher(text);
        try {
            if (v4.matches()) {
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return Optional.empty();
                    }
                    bytes[i] = (byte) octet;
                }
                return Optional.of(InetAddress.getByAddress(bytes));
            }
            // contains ':' and starts with a hex digit or ':' → parsed as an IPv6 literal, never looked up in DNS
            if (text.indexOf(':') >= 0 && IPV6.matcher(text).matches()) {
                return Optional.of(InetAddress.getByName(text));
            }
        } catch (UnknownHostException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Loopback, private (RFC 1918, fc00::/7), link-local, carrier-grade NAT, unspecified or multicast. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && (second & 0xC0) == 64);
        }
        return (b[0] & 0xFE) == 0xFC;
    }
}
