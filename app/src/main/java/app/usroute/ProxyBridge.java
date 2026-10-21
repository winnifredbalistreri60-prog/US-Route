package app.usroute;

import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local CONNECT-only HTTP proxy bridge to a selected upstream proxy. */
public final class ProxyBridge implements AutoCloseable {
    private static final int HEADER_LIMIT = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int PROBE_TIMEOUT_MS = 8_000;
    private static final int RELAY_TIMEOUT_MS = 120_000;
    private static final int MAX_CLIENTS = 64;
    private static final String PROBE_HOST = "ipwho.is";

    private final Object lock = new Object();
    private final Set<Socket> liveSockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_CLIENTS, new DaemonFactory());
    private final ExecutorService relays = Executors.newCachedThreadPool(new DaemonFactory());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ProxyProfile selected;
    private ServerSocket server;
    private Thread acceptThread;

    public ProxyBridge() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(false);
        listener.bind(new InetSocketAddress("127.0.0.1", 0), MAX_CLIENTS);
        server = listener;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "usroute-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        ServerSocket current = server;
        return current == null || current.isClosed() ? -1 : current.getLocalPort();
    }

    /** Switch profiles atomically and close all existing tunnels before returning. */
    public void select(ProxyProfile profile) {
        if (profile == null) throw new IllegalArgumentException("Profile is required");
        synchronized (lock) {
            ensureOpen();
            selected = profile;
            closeTrackedSockets();
        }
    }

    /** Disable upstream forwarding and terminate existing tunnels. */
    public void block() {
        synchronized (lock) {
            if (closed.get()) return;
            selected = null;
            closeTrackedSockets();
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) return;
            selected = null;
            ServerSocket listener = server;
            server = null;
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            closeTrackedSockets();
        }
        workers.shutdownNow();
        relays.shutdownNow();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Proxy bridge is closed");
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket client;
            try {
                ServerSocket listener = server;
                if (listener == null) return;
                client = listener.accept();
                client.setTcpNoDelay(true);
                synchronized (lock) {
                    if (closed.get() || liveSockets.size() >= MAX_CLIENTS * 2) { closeQuietly(client); continue; }
                    liveSockets.add(client);
                }
                try {
                    workers.execute(new Runnable() {
                        @Override public void run() { handleClient(client); }
                    });
                } catch (RuntimeException e) { forgetAndClose(client); }
            } catch (SocketException e) {
                if (!closed.get()) { /* listener failed; avoid spinning */ close(); }
                return;
            } catch (IOException e) {
                if (!closed.get()) close();
                return;
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            String header = readHeader(client.getInputStream());
            Request request = parseConnect(header);
            if (request == null) { writeError(client, 400, "Bad Request"); return; }
            if (request.port != 443) { writeError(client, 403, "Forbidden"); return; }
            ProxyProfile profile;
            synchronized (lock) {
                if (closed.get() || selected == null) { writeError(client, 503, "Unavailable"); return; }
                profile = selected;
            }
            upstream = connect(profile, request.host, request.port);
            synchronized (lock) {
                if (closed.get() || selected != profile || !liveSockets.contains(client)) {
                    closeQuietly(upstream); upstream = null; return;
                }
                liveSockets.add(upstream);
            }
            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final Socket remote = upstream;
            relay(client, remote);
        } catch (IOException e) {
            if (!client.isClosed()) writeError(client, 502, "Bad Gateway");
        } finally {
            if (upstream != null) forgetAndClose(upstream);
            forgetAndClose(client);
        }
    }

    private void relay(final Socket a, final Socket b) {
        try {
            try {
                a.setSoTimeout(RELAY_TIMEOUT_MS);
                b.setSoTimeout(RELAY_TIMEOUT_MS);
            } catch (IOException e) {
                return;
            }
            final AtomicBoolean done = new AtomicBoolean(false);
            Runnable ab = new Runnable() { @Override public void run() { copy(a, b, done); } };
            Runnable ba = new Runnable() { @Override public void run() { copy(b, a, done); } };
            relays.execute(ab);
            relays.execute(ba);
            while (!done.get() && !closed.get() && !a.isClosed() && !b.isClosed()) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            done.set(true);
        } catch (RuntimeException ignored) { }
        finally { forgetAndClose(a); forgetAndClose(b); }
    }

    private static void copy(Socket src, Socket dst, AtomicBoolean done) {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = src.getInputStream(); OutputStream out = dst.getOutputStream();
            while (!done.get()) {
                int n = in.read(buf);
                if (n < 0) break;
                out.write(buf, 0, n); out.flush();
            }
        } catch (IOException ignored) { }
        finally { done.set(true); closeQuietly(src); closeQuietly(dst); }
    }

    /** Connect only through the supplied proxy; never falls back to a direct target connection. */
    public static Socket connect(ProxyProfile p, String target, int port) throws IOException {
        if (p == null || target == null || target.isEmpty() || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0)
            throw new IOException("Invalid proxy target");
        if (port < 1 || port > 65535) throw new IOException("Invalid proxy target port");
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress(p.host, p.port), CONNECT_TIMEOUT_MS);
            transport.setSoTimeout(CONNECT_TIMEOUT_MS);
            Socket negotiated = transport;
            if ("https".equals(p.type)) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(transport, p.host, p.port, true);
                configureVerifiedTls(tls, p.host);
                tls.startHandshake();
                negotiated = tls;
            }
            if ("socks5".equals(p.type)) socksHandshake(negotiated, p, target, port);
            else httpConnect(negotiated, p, target, port);
            negotiated.setSoTimeout(RELAY_TIMEOUT_MS);
            negotiated.setTcpNoDelay(true);
            return negotiated;
        } catch (IOException | RuntimeException e) {
            closeQuietly(transport);
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Proxy connection failed");
        }
    }

    private static void httpConnect(Socket s, ProxyProfile p, String target, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        String authority = authority(target, port);
        StringBuilder request = new StringBuilder("CONNECT ").append(authority)
                .append(" HTTP/1.1\r\nHost: ").append(authority).append("\r\n");
        if (p.username != null && !p.username.isEmpty()) {
            String creds = p.username + ":" + (p.password == null ? "" : p.password);
            request.append("Proxy-Authorization: Basic ").append(Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        }
        request.append("Connection: keep-alive\r\n\r\n");
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII)); out.flush();
        String response = readHeader(s.getInputStream());
        String first = response.substring(0, response.indexOf("\r\n"));
        String[] parts = first.split(" ", 3);
        if (parts.length < 2 || !parts[0].matches("HTTP/1\\.[01]") || !"200".equals(parts[1]))
            throw new IOException("Upstream proxy rejected CONNECT");
    }

    private static void socksHandshake(Socket s, ProxyProfile p, String target, int port) throws IOException {
        InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
        boolean auth = p.username != null && !p.username.isEmpty();
        out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0}); out.flush();
        int ver = readByte(in), method = readByte(in);
        if (ver != 5 || method == 255 || (method == 2 && !auth) || (method != 0 && method != 2)) throw new IOException("SOCKS authentication negotiation failed");
        if (method == 2) {
            byte[] user = p.username.getBytes(StandardCharsets.UTF_8), pass = (p.password == null ? "" : p.password).getBytes(StandardCharsets.UTF_8);
            if (user.length == 0 || user.length > 255 || pass.length > 255) throw new IOException("Invalid SOCKS credentials");
            out.write(new byte[]{1, (byte)user.length}); out.write(user); out.write((byte)pass.length); out.write(pass); out.flush();
            if (readByte(in) != 1 || readByte(in) != 0) throw new IOException("SOCKS authentication failed");
        }
        byte[] host = target.getBytes(StandardCharsets.US_ASCII);
        if (host.length == 0 || host.length > 255 || !StandardCharsets.US_ASCII.newEncoder().canEncode(target)) throw new IOException("Invalid SOCKS target");
        out.write(new byte[]{5, 1, 0, 3, (byte)host.length}); out.write(host); out.write(new byte[]{(byte)(port >>> 8), (byte)port}); out.flush();
        int v = readByte(in), rep = readByte(in); readByte(in); int atyp = readByte(in);
        if (v != 5 || rep != 0) throw new IOException("SOCKS proxy rejected target");
        int addrLen;
        if (atyp == 1) addrLen = 4; else if (atyp == 4) addrLen = 16; else if (atyp == 3) addrLen = readByte(in); else throw new IOException("Invalid SOCKS response");
        skipFully(in, addrLen + 2);
    }

    public static ProbeResult probe(ProxyProfile profile) {
        long start = System.nanoTime();
        String ip = null, country = null, error = null; boolean ok = false;
        try (Socket tunnel = connect(profile, PROBE_HOST, 443)) {
            tunnel.setSoTimeout(PROBE_TIMEOUT_MS);
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tunnel, PROBE_HOST, 443, true);
            configureVerifiedTls(tls, PROBE_HOST);
            tls.setSoTimeout(PROBE_TIMEOUT_MS); tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + PROBE_HOST + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
            String raw = readHttpResponse(tls.getInputStream());
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) throw new IOException("Invalid probe response");
            String status = raw.substring(0, raw.indexOf("\r\n"));
            if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("Probe service returned an error");
            JSONObject json = new JSONObject(raw.substring(split + 4));
            if (json.has("ip") && !json.isNull("ip")) ip = json.getString("ip");
            if (json.has("country_code") && !json.isNull("country_code")) country = json.getString("country_code");
            if (json.has("success") && !json.isNull("success") && !json.getBoolean("success")) throw new IOException("Probe service reported failure");
            ok = "US".equals(country);
            if (!ok) error = country == null ? "Probe did not return a country code" : "Proxy exit is not in the US";
            tls.close();
        } catch (IOException | JSONException e) {
            error = safeError(e);
        } catch (RuntimeException e) {
            error = "Proxy probe failed";
        }
        return new ProbeResult(profile, ok, (System.nanoTime() - start) / 1_000_000L, ip, country, error);
    }

    private static String readHttpResponse(InputStream in) throws IOException {
        String headers = readHeader(in);
        int boundary = headers.indexOf("\r\n\r\n");
        String body = headers.substring(boundary + 4);
        String lower = headers.toLowerCase(Locale.ROOT);
        if (lower.contains("transfer-encoding: chunked")) {
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            while (true) {
                String line = readLine(in, 128); int semicolon = line.indexOf(';');
                int size; try { size = Integer.parseInt(semicolon < 0 ? line : line.substring(0, semicolon), 16); } catch (NumberFormatException e) { throw new IOException("Invalid probe response"); }
                if (size < 0 || decoded.size() + size > 64 * 1024) throw new IOException("Probe response too large");
                if (size == 0) { readLine(in, HEADER_LIMIT); break; }
                byte[] chunk = new byte[size]; readFully(in, chunk); decoded.write(chunk); expectCrlf(in);
            }
            body = new String(decoded.toByteArray(), StandardCharsets.UTF_8);
        } else {
            String len = headerValue(headers, "content-length");
            if (len != null) {
                long n; try { n = Long.parseLong(len); } catch (NumberFormatException e) { throw new IOException("Invalid probe response"); }
                if (n < 0 || n > 64 * 1024) throw new IOException("Probe response too large");
                byte[] bytes = new byte[(int)n]; readFully(in, bytes); body = new String(bytes, StandardCharsets.UTF_8);
            } else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) >= 0) { if (bytes.size() + n > 64 * 1024) throw new IOException("Probe response too large"); bytes.write(buf, 0, n); }
                body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return headers.substring(0, boundary + 4) + body;
    }

    private static String headerValue(String headers, String wanted) {
        for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(wanted)) return line.substring(colon + 1).trim(); }
        return null;
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int state = 0;
        while (bytes.size() < HEADER_LIMIT) {
            int b = in.read(); if (b < 0) throw new IOException("Connection closed"); bytes.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') { state++; if (state == 4) break; }
            else state = b == '\r' ? 1 : 0;
        }
        if (state != 4) throw new IOException("Header too large");
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readLine(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int prev = -1;
        while (b.size() < cap) { int c = in.read(); if (c < 0) throw new IOException("Connection closed"); if (prev == '\r' && c == '\n') { byte[] a = b.toByteArray(); return new String(a, 0, a.length - 1, StandardCharsets.US_ASCII); } b.write(c); prev = c; }
        throw new IOException("Header too large");
    }

    private static Request parseConnect(String header) {
        String first = header.substring(0, header.indexOf("\r\n"));
        String[] parts = first.split(" ");
        if (parts.length != 3 || !"CONNECT".equals(parts[0]) || !("HTTP/1.0".equals(parts[2]) || "HTTP/1.1".equals(parts[2]))) return null;
        String authority = parts[1], host; int port; int colon;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0 || close + 1 >= authority.length() || authority.charAt(close + 1) != ':') return null;
            host = authority.substring(1, close);
            if (!validIpv6(host)) return null;
            colon = close + 1;
        } else {
            colon = authority.lastIndexOf(':'); if (colon <= 0 || authority.indexOf(':') != colon) return null;
            host = authority.substring(0, colon);
            if (!validDomain(host)) return null;
        }
        try { port = Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException e) { return null; }
        return port > 0 && port <= 65535 ? new Request(host, port) : null;
    }

    private static boolean validDomain(String h) { return h.length() <= 253 && h.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"); }
    private static boolean validIpv6(String h) { return h.matches("(?i)[0-9a-f:.%]+") && h.indexOf(':') >= 0; }
    private static String authority(String host, int port) { return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port; }

    private static void configureVerifiedTls(SSLSocket socket, String host) throws IOException {
        SSLParameters params = socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.matches("[0-9a-fA-F:.%]+")) params.setServerNames(Collections.<javax.net.ssl.SNIServerName>singletonList(new javax.net.ssl.SNIHostName(host)));
        socket.setSSLParameters(params);
    }

    private static int readByte(InputStream in) throws IOException { int v = in.read(); if (v < 0) throw new IOException("Connection closed"); return v; }
    private static void skipFully(InputStream in, int n) throws IOException { while (n > 0) { long s = in.skip(n); if (s <= 0) { readByte(in); s = 1; } n -= (int)s; } }
    private static void readFully(InputStream in, byte[] data) throws IOException { int at = 0; while (at < data.length) { int n = in.read(data, at, data.length - at); if (n < 0) throw new IOException("Connection closed"); at += n; } }
    private static void expectCrlf(InputStream in) throws IOException { if (readByte(in) != '\r' || readByte(in) != '\n') throw new IOException("Invalid probe response"); }

    private static void writeError(Socket socket, int code, String phrase) {
        try { OutputStream out = socket.getOutputStream(); out.write(("HTTP/1.1 " + code + " " + phrase + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); } catch (IOException ignored) { }
    }
    private static String safeError(Exception e) {
        // Never surface exception messages: transport implementations can include proxy details.
        if (e instanceof JSONException) return "Invalid probe response";
        if (e instanceof java.net.SocketTimeoutException) return "Proxy probe timed out";
        if (e instanceof javax.net.ssl.SSLException) return "TLS verification failed";
        return "Proxy probe failed";
    }
    private void closeTrackedSockets() {
        synchronized (liveSockets) { for (Socket s : new ArrayList<Socket>(liveSockets)) closeQuietly(s); liveSockets.clear(); }
    }
    private void forgetAndClose(Socket s) { synchronized (liveSockets) { liveSockets.remove(s); } closeQuietly(s); }
    private static void closeQuietly(Socket s) { if (s != null) try { s.close(); } catch (IOException ignored) { } }

    private static final class Request { final String host; final int port; Request(String h, int p) { host = h; port = p; } }
    private static final class DaemonFactory implements ThreadFactory {
        private int next;
        @Override public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, "usroute-proxy-worker-" + (++next)); t.setDaemon(true); return t; }
    }

    public static final class ProbeResult {
        public final ProxyProfile profile;
        public final boolean ok;
        public final long latencyMs;
        public final String ip;
        public final String country;
        public final String error;
        public ProbeResult(ProxyProfile profile, boolean ok, long latencyMs, String ip, String country, String error) {
            this.profile = profile; this.ok = ok; this.latencyMs = latencyMs; this.ip = ip; this.country = country; this.error = error;
        }
    }
}
