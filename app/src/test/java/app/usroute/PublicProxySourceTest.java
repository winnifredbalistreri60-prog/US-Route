package app.usroute;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class PublicProxySourceTest {
    @Test public void parsesValidEntriesAndMetadata() throws Exception {
        List<ProxyProfile> result = PublicProxySource.parse("http://8.8.8.8:8080\nhttps://1.1.1.1:443\nsocks5://9.9.9.9:1080");
        assertEquals(3, result.size());
        Set<String> types = new HashSet<>();
        for (ProxyProfile p : result) {
            types.add(p.type);
            assertTrue(p.id.startsWith("public-"));
            assertTrue(p.name.contains("Free " + p.type + " proxy " + p.host));
            assertNull(p.username);
            assertNull(p.password);
        }
        assertEquals(3, types.size());
    }

    @Test public void filtersUnsupportedAndMalformedSchemesAndAuthority() throws Exception {
        List<ProxyProfile> result = PublicProxySource.parse("ftp://8.8.8.8:21\nhttp://8.8.8.8:0\nhttp://8.8.8.8:65536\nhttp://host:80\nhttp://8.8.8.8:80/path\nhttp://8.8.8.8:80?x=1\nhttp://8.8.8.8:80#x\nhttps://1.1.1.1:443");
        assertEquals(1, result.size());
        assertEquals("https", result.get(0).type);
    }

    @Test public void rejectsPrivateLoopbackAndReservedAddresses() throws Exception {
        List<ProxyProfile> result = PublicProxySource.parse("http://10.0.0.1:80\nhttp://127.0.0.1:80\nhttp://192.168.1.1:80\nhttp://169.254.2.3:80\nhttp://198.51.100.4:80\nhttp://203.0.113.7:80\nhttp://224.0.0.1:80\nhttp://8.8.4.4:53");
        assertEquals(1, result.size());
        assertEquals("8.8.4.4", result.get(0).host);
    }

    @Test public void deduplicatesEndpoints() throws Exception {
        List<ProxyProfile> result = PublicProxySource.parse("http://8.8.8.8:80\nhttp://8.8.8.8:80\nhttps://8.8.8.8:80");
        assertEquals(2, result.size());
    }

    @Test public void capsResultsAndSpreadsProtocols() throws Exception {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            int second = i / 250, third = (i / 250) % 250, fourth = i % 250 + 1;
            input.append("http://8.").append(second).append('.').append(third).append('.').append(fourth).append(':').append(1000+i).append('\n');
            input.append("https://9.").append(second).append('.').append(third).append('.').append(fourth).append(':').append(2000+i).append('\n');
            input.append("socks5://11.").append(second).append('.').append(third).append('.').append(fourth).append(':').append(3000+i).append('\n');
        }
        List<ProxyProfile> result = PublicProxySource.parse(input.toString());
        assertEquals(60, result.size());
        int http=0, https=0, socks=0;
        for (ProxyProfile p : result) { if (p.type.equals("http")) http++; else if (p.type.equals("https")) https++; else socks++; }
        assertEquals(20, http); assertEquals(20, https); assertEquals(20, socks);
    }

    @Test public void rejectsCredentialsAndEmptyValidSet() throws Exception {
        try { PublicProxySource.parse("http://user:pass@8.8.8.8:80"); fail("expected no valid proxies"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void starterIsPresentFirstAndNotDuplicated() throws Exception {
        List<ProxyProfile> result = PublicProxySource.withStarter(PublicProxySource.parse("socks5://67.207.92.87:1088\nhttp://8.8.8.8:80"));
        assertEquals(2, result.size());
        assertEquals("67.207.92.87", result.get(0).host);
        assertEquals(1088, result.get(0).port);
        assertEquals(1, PublicProxySource.withStarter(java.util.Collections.emptyList()).size());
    }

    @Test public void loadsBundledSnapshotLocally() throws Exception {
        try (InputStream input = new FileInputStream("src/main/assets/us-proxies.txt")) {
            List<ProxyProfile> result = PublicProxySource.loadSnapshot(input);
            assertTrue(result.size() > 1);
            assertTrue(result.size() <= 60);
            assertEquals("67.207.92.87", result.get(0).host);
            assertEquals(1088, result.get(0).port);
        }
    }

    @Test public void snapshotRejectsMoreThanOneMiB() throws Exception {
        byte[] oversized = new byte[1024 * 1024 + 1];
        try {
            PublicProxySource.loadSnapshot(new ByteArrayInputStream(oversized));
            fail("expected size limit rejection");
        } catch (java.io.IOException expected) { }
    }
}
