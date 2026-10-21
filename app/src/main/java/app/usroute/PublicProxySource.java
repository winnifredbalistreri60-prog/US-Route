package app.usroute;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Retrieves and validates the public US proxy list. The feed's country label is a claim;
 * actual egress country must be verified by the application's UI probe. */
public final class PublicProxySource {
    private static final String PRIMARY = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/US/data.txt";
    private static final String FALLBACK = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/US/data.txt";
    // Public endpoint verified during development; never trusted without a fresh US probe.
    private static final String STARTER = "socks5://67.207.92.87:1088";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_RESULTS = 60;
    private static final long TOTAL_DEADLINE_MS = 20_000L;

    private PublicProxySource() {}

    public static List<ProxyProfile> fetch() throws Exception {
        for (String endpoint : new String[] { PRIMARY, FALLBACK }) {
            try {
                return withStarter(parse(download(endpoint)));
            } catch (Exception e) {
                // Try the second pinned HTTPS source before the starter fallback.
            }
        }
        // A stale starter may fail; the caller still enforces the normal TLS/US probe.
        return withStarter(Collections.emptyList());
    }

    /** Loads a bundled/local snapshot without closing the caller-owned stream. */
    public static List<ProxyProfile> loadSnapshot(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > MAX_BYTES) throw new java.io.IOException("Proxy snapshot exceeds size limit");
            out.write(buffer, 0, count);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return withStarter(parse(text));
    }

    static List<ProxyProfile> withStarter(List<ProxyProfile> fresh) throws Exception {
        ProxyProfile starter = parse(STARTER).get(0);
        List<ProxyProfile> result = new ArrayList<>();
        result.add(starter);
        for (ProxyProfile candidate : fresh) {
            if (result.size() >= MAX_RESULTS) break;
            if (!starter.id.equals(candidate.id)) result.add(candidate);
        }
        return result;
    }

    private static String download(String address) throws Exception {
        long deadline = System.nanoTime() + TOTAL_DEADLINE_MS * 1_000_000L;
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1)
            throw new java.io.IOException("Rejected source URL");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/plain");
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException("Proxy source HTTP status " + status);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_BYTES) throw new java.io.IOException("Proxy source exceeds size limit");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline) throw new java.io.IOException("Proxy source read deadline exceeded");
                    if (out.size() + count > MAX_BYTES) throw new java.io.IOException("Proxy source exceeds size limit");
                    out.write(buffer, 0, count);
                }
                if (System.nanoTime() > deadline) throw new java.io.IOException("Proxy source read deadline exceeded");
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    public static List<ProxyProfile> parse(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        Set<String> seen = new HashSet<>();
        List<ProxyProfile> entries = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.length() == 0 || line.length() > 128) continue;
            int schemeEnd = line.indexOf("://");
            if (schemeEnd <= 0) continue;
            String scheme = line.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https") || scheme.equals("socks5"))) continue;
            String authority = line.substring(schemeEnd + 3);
            int colon = authority.indexOf(':');
            if (colon <= 0 || colon != authority.lastIndexOf(':')) continue;
            String ip = authority.substring(0, colon);
            String portText = authority.substring(colon + 1);
            if (!isPublicIpv4(ip) || !portText.matches("[0-9]{1,5}")) continue;
            int port;
            try { port = Integer.parseInt(portText); } catch (NumberFormatException e) { continue; }
            if (port < 1 || port > 65535) continue;
            String key = scheme + "://" + ip + ":" + port;
            if (seen.add(key)) entries.add(new ProxyProfile("public-" + scheme + "-" + ip.replace('.', '-') + "-" + port,
                    "Free " + scheme + " proxy " + ip, scheme, ip, port, null, null));
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("No valid public proxies in source");
        Collections.shuffle(entries, ThreadLocalRandom.current());
        return spreadAndLimit(entries, MAX_RESULTS);
    }

    private static List<ProxyProfile> spreadAndLimit(List<ProxyProfile> candidates, int limit) {
        List<ProxyProfile> result = new ArrayList<>();
        String[] protocols = { "http", "https", "socks5" };
        boolean progress = true;
        while (result.size() < limit && progress) {
            progress = false;
            for (String protocol : protocols) {
                if (result.size() >= limit) break;
                for (int i = 0; i < candidates.size(); i++) {
                    if (protocol.equals(candidates.get(i).type)) {
                        result.add(candidates.remove(i));
                        progress = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static boolean isPublicIpv4(String ip) {
        String[] p = ip.split("\\.", -1);
        if (p.length != 4) return false;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!p[i].matches("0|[1-9][0-9]{0,2}")) return false;
            b[i] = Integer.parseInt(p[i]);
            if (b[i] > 255) return false;
        }
        int a = b[0], second = b[1];
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && second >= 64 && second <= 127) return false;
        if (a == 169 && second == 254) return false;
        if (a == 172 && second >= 16 && second <= 31) return false;
        if (a == 192 && (second == 0 || second == 168)) return false;
        if (a == 198 && (second == 18 || second == 19 || second == 51)) return false;
        if (a == 203 && second == 0 && b[2] == 113) return false;
        // Documentation range 192.0.2.0/24; protocol assignments, benchmark, and reserved blocks.
        if (a == 192 && second == 0 && (b[2] == 2 || b[2] == 0)) return false;
        if (a == 198 && second == 51 && b[2] == 100) return false;
        if (a == 192 && second == 88 && b[2] == 99) return false;
        if (a == 255) return false;
        return true;
    }
}
