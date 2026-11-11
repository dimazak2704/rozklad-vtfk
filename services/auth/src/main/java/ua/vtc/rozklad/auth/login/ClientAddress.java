package ua.vtc.rozklad.auth.login;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * The client's address and its network ({@code /24} for IPv4, {@code /64} for IPv6). The address is
 * {@code getRemoteAddr()} after Tomcat's RemoteIpValve, which trusts {@code X-Forwarded-For} only from the
 * internal proxies (nginx, Caddy).
 *
 * @param ip textual address, or {@code null} when it cannot be parsed
 * @param prefix network in CIDR form, or {@code null}
 */
public record ClientAddress(String ip, String prefix) {

    public static ClientAddress of(HttpServletRequest request) {
        return parse(request.getRemoteAddr());
    }

    public static ClientAddress parse(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return new ClientAddress(null, null);
        }
        try {
            InetAddress address = InetAddress.ofLiteral(remoteAddr.strip());
            byte[] bytes = address.getAddress();
            int bits = address instanceof Inet4Address ? 24 : 64;
            byte[] network = Arrays.copyOf(bytes, bytes.length);
            for (int i = bits / 8; i < network.length; i++) {
                network[i] = 0;
            }
            String prefix = InetAddress.getByAddress(network).getHostAddress() + "/" + bits;
            return new ClientAddress(address.getHostAddress(), prefix);
        } catch (IllegalArgumentException | java.net.UnknownHostException ex) {
            return new ClientAddress(null, null);
        }
    }
}
