package Server.http;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Resolves client/protocol information only across explicitly trusted proxy hops. */
public final class ForwardedRequestResolver {

    private static final List<Subnet> TRUSTED_PROXIES = loadTrustedProxies();

    private ForwardedRequestResolver() {}

    public static String clientIp(HttpExchange exchange) {
        InetAddress peer = remoteAddress(exchange);
        if (peer == null) return "unknown";
        if (!isTrusted(peer)) return peer.getHostAddress();

        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer.getHostAddress();
        String[] hops = forwarded.split(",");
        InetAddress leftmostValid = null;
        for (int index = hops.length - 1; index >= 0; index--) {
            InetAddress candidate = parseAddress(hops[index]);
            if (candidate == null) continue;
            leftmostValid = candidate;
            if (!isTrusted(candidate)) return candidate.getHostAddress();
        }
        return leftmostValid == null ? peer.getHostAddress() : leftmostValid.getHostAddress();
    }

    public static boolean isSecure(HttpExchange exchange) {
        InetAddress peer = remoteAddress(exchange);
        if (peer == null || !isTrusted(peer)) return false;
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null) return false;
        String first = proto.split(",", 2)[0].trim();
        return "https".equalsIgnoreCase(first);
    }

    static boolean isTrusted(InetAddress address) {
        return TRUSTED_PROXIES.stream().anyMatch(subnet -> subnet.contains(address));
    }

    private static InetAddress remoteAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange == null ? null : exchange.getRemoteAddress();
        return remote == null ? null : remote.getAddress();
    }

    private static InetAddress parseAddress(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("[") && value.contains("]")) value = value.substring(1, value.indexOf(']'));
        try {
            // Forwarded client hops must be IP literals; reject names to avoid DNS lookups.
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
            return InetAddress.getByName(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Subnet> loadTrustedProxies() {
        List<Subnet> result = new ArrayList<>();
        result.add(Subnet.parse("127.0.0.0/8"));
        result.add(Subnet.parse("::1/128"));
        String configured = System.getenv("TRUSTED_PROXY_CIDRS");
        if (configured != null) {
            for (String value : configured.split(",")) {
                if (!value.isBlank()) result.add(Subnet.parse(value.trim()));
            }
        }
        return List.copyOf(result);
    }

    private record Subnet(byte[] network, int prefixLength) {
        static Subnet parse(String value) {
            try {
                String[] parts = value.split("/", 2);
                InetAddress address = InetAddress.getByName(parts[0]);
                int bits = address.getAddress().length * 8;
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits;
                if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("invalid prefix");
                return new Subnet(address.getAddress(), prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) if (candidate[i] != network[i]) return false;
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
