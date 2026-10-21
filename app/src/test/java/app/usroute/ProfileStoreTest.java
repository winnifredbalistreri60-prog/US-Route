package app.usroute;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ProfileStoreTest {
    private static String profile(String id, String country, String type, String host, int port) {
        return "{\"id\":\"" + id + "\",\"country\":\"" + country + "\",\"type\":\"" + type + "\",\"host\":\"" + host + "\",\"port\":" + port + "}";
    }
    private static String doc(String... entries) { return "{\"profiles\":[" + String.join(",", entries) + "]}"; }
    private static void rejects(String json) throws Exception {
        try { ProfileStore.parse(json); fail("Expected invalid profile input to be rejected: " + json); }
        catch (IllegalArgumentException expected) { }
        catch (Exception expected) { }
    }

    @Test public void parsesEmptyList() throws Exception {
        assertTrue(ProfileStore.parse("{\"profiles\":[]}").isEmpty());
    }
    @Test public void parsesValidHttpAndSocks5() throws Exception {
        List<ProxyProfile> p = ProfileStore.parse(doc(profile("a","US","http","proxy.example",8080), profile("b","us","socks5","127.0.0.1",1080)));
        assertEquals(2, p.size()); assertEquals("http", p.get(0).type); assertEquals("socks5", p.get(1).type);
    }
    @Test public void rejectsInvalidCountryTypePortHostCrlfDuplicateAndOverTwenty() throws Exception {
        rejects(doc(profile("a","CA","http","proxy.example",80)));
        rejects(doc(profile("a","US","ftp","proxy.example",80)));
        rejects(doc(profile("a","US","http","proxy.example",0)));
        rejects(doc(profile("a","US","http","bad host",80)));
        rejects(doc(profile("a\r\nX: y","US","http","proxy.example",80)));
        rejects(doc(profile("dup","US","http","proxy.example",80), profile("dup","US","http","proxy.example",81)));
        String[] many = new String[21]; for (int i=0;i<many.length;i++) many[i]=profile("p"+i,"US","http","proxy.example",80);
        rejects(doc(many));
    }
}
