package app.usroute;

import java.util.Objects;

/** Immutable credentials and endpoint settings for an upstream proxy. */
public final class ProxyProfile {
    public final String id;
    public final String name;
    /** One of "http", "https", or "socks5". */
    public final String type;
    public final String host;
    public final int port;
    public final String username;
    public final String password;

    public ProxyProfile(String id, String name, String type, String host, int port,
                        String username, String password) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type").toLowerCase(java.util.Locale.ROOT);
        this.host = Objects.requireNonNull(host, "host");
        if (!("http".equals(this.type) || "https".equals(this.type) || "socks5".equals(this.type))) {
            throw new IllegalArgumentException("Unsupported proxy type");
        }
        if (host.isEmpty() || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid proxy host");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid proxy port");
        this.port = port;
        this.username = username;
        this.password = password;
    }
}
