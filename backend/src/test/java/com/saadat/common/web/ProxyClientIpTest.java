package com.saadat.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Client address for geolocation: Caddy's X-Forwarded-For hop from the proxy, else the peer; never private. */
class ProxyClientIpTest {

    /** Caddy's address on the compose network. */
    private static final String PROXY = "172.18.0.3";

    @Test
    void behindTheProxyTheClientIsTheLastForwardedHop() {
        assertThat(address(fromProxy("203.0.113.7"))).contains("203.0.113.7");
        assertThat(address(fromProxy("198.51.100.1, 192.0.2.44", "203.0.113.9"))).contains("203.0.113.9");
        assertThat(address(fromProxy("198.51.100.1 ,203.0.113.10 , "))).contains("203.0.113.10");
    }

    @Test
    void aPublicPeerIsTheClientWhateverItForwards() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader(ClientIp.HEADER_X_FORWARDED_FOR, "203.0.113.7");

        assertThat(address(request)).contains("198.51.100.20");
    }

    @Test
    void filterWrappersThatRewriteTheAddressAreIgnored() {
        // ForwardedHeaderFilter rewrites getRemoteAddr() from a client-supplied "Forwarded: for=…"
        // and hides the X-Forwarded-For header
        HttpServletRequest wrapped = new HttpServletRequestWrapper(fromProxy("203.0.113.7")) {
            @Override
            public String getRemoteAddr() {
                return "198.51.100.66";
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return Collections.emptyEnumeration();
            }
        };

        assertThat(address(wrapped)).contains("203.0.113.7");
    }

    @Test
    void privateLoopbackLinkLocalAndMalformedAddressesAreNotLookedUp() {
        List<String> hops = List.of("10.1.2.3", "172.18.0.5", "192.168.1.10", "127.0.0.1", "100.64.3.4",
                "169.254.1.1", "0.0.0.0", "224.0.0.1", "::1", "fd12:3456::1", "fe80::1", "::ffff:10.0.0.1",
                "example.com", "localhost", "1.2.3.999", "1.2.3", "abc", "1:2", "fe80::1%eth0", "unknown", "_", "");
        for (String hop : hops) {
            assertThat(address(fromProxy(hop))).as("X-Forwarded-For: %s", hop).isEmpty();
        }
        assertThat(address(new MockHttpServletRequest())).as("loopback peer, no header").isEmpty();
        MockHttpServletRequest proxyOnly = new MockHttpServletRequest();
        proxyOnly.setRemoteAddr(PROXY);
        assertThat(address(proxyOnly)).as("the proxy itself").isEmpty();
    }

    @Test
    void acceptsBracketsPortsAndMappedAddresses() {
        assertThat(address(fromProxy("[2001:db8::7]:443"))).contains("2001:db8:0:0:0:0:0:7");
        assertThat(address(fromProxy("2001:db8::7"))).contains("2001:db8:0:0:0:0:0:7");
        assertThat(address(fromProxy("203.0.113.7:51234"))).contains("203.0.113.7");
        assertThat(address(fromProxy("::ffff:203.0.113.8"))).contains("203.0.113.8");
    }

    private static MockHttpServletRequest fromProxy(String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY);
        for (String value : forwardedFor) {
            request.addHeader(ClientIp.HEADER_X_FORWARDED_FOR, value);
        }
        return request;
    }

    private static Optional<String> address(HttpServletRequest request) {
        return ProxyClientIp.publicAddress(request).map(InetAddress::getHostAddress);
    }
}
