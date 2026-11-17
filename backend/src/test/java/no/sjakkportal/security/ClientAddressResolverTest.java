package no.sjakkportal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {

    private static MockHttpServletRequest request(String remote, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (header != null) {
            request.addHeader(header, value);
        }
        return request;
    }

    @Test
    void withoutConfigurationTheConnectionAddressIsUsedAndHeadersAreIgnored() {
        ClientAddressResolver resolver = new ClientAddressResolver(null, 1);
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "203.0.113.9"))).isEqualTo("10.0.0.5");
    }

    @Test
    void takesTheEntryAddedByTheLastTrustedProxy() {
        ClientAddressResolver resolver = new ClientAddressResolver("X-Forwarded-For", 1);
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    /** A client can put anything at the start of the header; only entries appended by our proxies count. */
    @Test
    void spoofedEntriesOnTheLeftAreIgnored() {
        ClientAddressResolver resolver = new ClientAddressResolver("X-Forwarded-For", 1);
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "1.2.3.4, 5.6.7.8, 198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void countsSeveralTrustedProxiesFromTheRight() {
        ClientAddressResolver resolver = new ClientAddressResolver("X-Forwarded-For", 2);
        // client, then proxy A appended the client, then proxy B appended proxy A
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "6.6.6.6, 198.51.100.7, 76.76.21.9"))).isEqualTo("198.51.100.7");
    }

    @Test
    void fallsBackToTheConnectionWhenTheHeaderIsMissingShortOrJunk() {
        ClientAddressResolver resolver = new ClientAddressResolver("X-Forwarded-For", 2);
        assertThat(resolver.resolve(request("10.0.0.5", null, null))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "198.51.100.7"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", " , "))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "X-Forwarded-For", "x".repeat(5000)))).isEqualTo("10.0.0.5");
        assertThat(new ClientAddressResolver("X-Forwarded-For", 1).resolve(request("10.0.0.5", "X-Forwarded-For", "<script>, not-an-ip")))
                .isEqualTo("10.0.0.5");
    }

    @Test
    void singleValueHeadersWork() {
        ClientAddressResolver resolver = new ClientAddressResolver("True-Client-IP", 1);
        assertThat(resolver.resolve(request("10.0.0.5", "True-Client-IP", "2001:db8::1"))).isEqualTo("2001:db8::1");
    }
}
