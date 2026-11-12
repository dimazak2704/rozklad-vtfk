package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;

/**
 * {@code X-Forwarded-For} counts only from the trusted proxies ({@code AUTH_TRUSTED_PROXIES}, loopback by
 * default), not from any private address: a client on the LAN cannot pick the IP the lockout sees.
 */
@AuthIntegrationTest
class ForwardedForIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aPrivateAddressThatIsNotATrustedProxyCannotChooseItsClientIp() throws SocketException {
        Optional<String> lan = privateAddress();
        assumeTrue(lan.isPresent(), "this machine has no private IPv4 address besides loopback");
        String identifier = "xff-" + System.nanoTime();
        Browser client = browsers.openAt(lan.get());
        client.forwardedFor("198.18.7.77");

        assertThat(Browser.location(client.signIn(identifier, "wrong-password"))).isEqualTo("/auth/login?error");

        assertThat(jdbc.sql("SELECT host(ip) FROM login_attempt WHERE identifier_hash = ?")
                .param(LoginAttempts.hash(identifier)).query(String.class).single()).isEqualTo(lan.get());
    }

    @Test
    void aLoopbackProxyIsTrusted() {
        Browser proxy = browsers.open("198.18.7.78");
        String identifier = "xff-" + System.nanoTime();

        proxy.signIn(identifier, "wrong-password");

        assertThat(jdbc.sql("SELECT host(ip) FROM login_attempt WHERE identifier_hash = ?")
                .param(LoginAttempts.hash(identifier)).query(String.class).single()).isEqualTo("198.18.7.78");
    }

    private static Optional<String> privateAddress() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .filter(nic -> {
                    try {
                        return nic.isUp() && !nic.isLoopback();
                    } catch (SocketException ex) {
                        return false;
                    }
                })
                .flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress())
                .map(InetAddress::getHostAddress)
                .findFirst();
    }
}
