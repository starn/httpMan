package httpman.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Lets HttpMan make "host X resolves to address Y" (like curl --resolve) without touching the URL:
 * the request keeps its original URL, Host header, SNI and certificate host name check.
 * Everything not overridden goes to the normal OS resolver. Requires JDK 18+.
 * Registered through META-INF/services/java.net.spi.InetAddressResolverProvider.
 */
public final class OverrideResolverProvider extends InetAddressResolverProvider {

    private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

    /** Map {@code host} to {@code target} (IP or host name); a null/blank target removes the mapping. */
    static void set(String host, String target) {
        String key = host.toLowerCase(Locale.ROOT);
        if (target == null || target.isBlank()) {
            OVERRIDES.remove(key);
        } else {
            OVERRIDES.put(key, target.trim());
        }
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String target = host == null ? null : OVERRIDES.get(host.toLowerCase(Locale.ROOT));
                if (target == null) {
                    return builtin.lookupByName(host, policy);
                }
                InetAddress a = InetAddress.getByName(target); // literal IPs are parsed, names use the normal DNS
                return Stream.of(InetAddress.getByAddress(host, a.getAddress()));
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "HttpMan host override";
    }
}
