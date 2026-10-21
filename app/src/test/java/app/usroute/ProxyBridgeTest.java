package app.usroute;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ProxyBridgeTest {
    private static final byte[] MARKER = new byte[]{0, 1, 2, 3, 65, 66};
    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int state=0;
        while (b.size()<16384) { int c=in.read(); if(c<0) throw new EOFException(); b.write(c);
            if ((state==0||state==2)&&c=='\r') state++; else if((state==1||state==3)&&c=='\n'){state++;if(state==4)break;} else state=c=='\r'?1:0;
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }
    private static void sendConnect(Socket s, String target) throws IOException {
        s.getOutputStream().write(("CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush();
    }
    private static String status(Socket s) throws IOException { return readHeader(s.getInputStream()).split("\\r\\n",2)[0]; }
    private static ProxyProfile profile(String type,String host,int port,String user,String pass) { return new ProxyProfile("id","fixture",type,host,port,user,pass); }
    private static int freePort() throws IOException { try(ServerSocket s=new ServerSocket(0)){return s.getLocalPort();} }
    private static void readFully(InputStream in, byte[] b) throws IOException { int off=0; while(off<b.length){int n=in.read(b,off,b.length-off); if(n<0)throw new EOFException();off+=n;} }

    @Test public void noProfileReturns503AndNeverDirectFallback() throws Exception {
        try(ProxyBridge bridge=new ProxyBridge(); Socket client=new Socket("127.0.0.1",bridge.getPort())) {
            sendConnect(client,"does-not-resolve.invalid:443"); assertTrue(status(client).startsWith("HTTP/1.1 503"));
        }
    }
    @Test public void forbiddenPortAndPlainGetAreRejected() throws Exception {
        try(ProxyBridge bridge=new ProxyBridge()) {
            bridge.select(profile("http","127.0.0.1",freePort(),"",""));
            try(Socket s=new Socket("127.0.0.1",bridge.getPort())) { sendConnect(s,"example.com:80"); assertTrue(status(s).startsWith("HTTP/1.1 403")); }
            try(Socket s=new Socket("127.0.0.1",bridge.getPort())) { s.getOutputStream().write("GET http://example.com/ HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); assertTrue(status(s).startsWith("HTTP/1.1 400")); }
        }
    }
    @Test public void httpProxyAuthTunnelRelaysBytesAndBlockClosesLiveTunnel() throws Exception {
        try(ServerSocket upstream=new ServerSocket(0); ProxyBridge bridge=new ProxyBridge()) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<Boolean> fixture=executor.submit(() -> {
                try(Socket s=upstream.accept()) {
                    String req=readHeader(s.getInputStream());
                    if(!req.contains("Proxy-Authorization: Basic dGVzdHVzZXI6dGVzdHBhc3M=")) return false;
                    s.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush();
                    byte[] buf=new byte[MARKER.length]; readFully(s.getInputStream(),buf); s.getOutputStream().write(buf); s.getOutputStream().flush();
                    int next=s.getInputStream().read(); return java.util.Arrays.equals(buf,MARKER) && next==-1;
                }
            });
            bridge.select(profile("http","127.0.0.1",upstream.getLocalPort(),"testuser","testpass"));
            try(Socket client=new Socket("127.0.0.1",bridge.getPort())) {
                client.setSoTimeout(5000); sendConnect(client,"target.example:443"); assertTrue(status(client).startsWith("HTTP/1.1 200"));
                client.getOutputStream().write(MARKER); client.getOutputStream().flush(); byte[] echoed=new byte[MARKER.length]; readFully(client.getInputStream(),echoed); assertArrayEquals(MARKER,echoed);
                bridge.block(); assertEquals(-1,client.getInputStream().read());
            }
            assertTrue(fixture.get(5,TimeUnit.SECONDS)); executor.shutdownNow();
        }
    }
    @Test public void selectedProxyConnectionFailureReturns502WithoutDirectFallback() throws Exception {
        try(ProxyBridge bridge=new ProxyBridge()) {
            bridge.select(profile("http","127.0.0.1",freePort(),"",""));
            try(Socket client=new Socket("127.0.0.1",bridge.getPort())) { client.setSoTimeout(5000); sendConnect(client,"example.com:443"); assertTrue(status(client).startsWith("HTTP/1.1 502")); }
        }
    }
    @Test public void socks5NegotiatesUsernamePasswordAndPassesDomainForRemoteDns() throws Exception {
        try(ServerSocket upstream=new ServerSocket(0); ProxyBridge bridge=new ProxyBridge()) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<Boolean> fixture=executor.submit(() -> {
                try(Socket s=upstream.accept()) {
                    InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
                    int v=in.read(), n=in.read(); byte[] methods=new byte[n]; readFully(in,methods);
                    out.write(new byte[]{5,2}); out.flush();
                    int av=in.read(), ul=in.read(); byte[] u=new byte[ul]; readFully(in,u); int pl=in.read(); byte[] p=new byte[pl]; readFully(in,p);
                    out.write(new byte[]{1,0}); out.flush();
                    int ver=in.read(), cmd=in.read(), rsv=in.read(), atyp=in.read(), hl=in.read(); byte[] host=new byte[hl]; readFully(in,host); int port=(in.read()<<8)|in.read();
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,0}); out.flush();
                    byte[] data=new byte[MARKER.length]; readFully(in,data); out.write(data); out.flush();
                    return v==5 && n==2 && methods[0]==0 && methods[1]==2 && av==1 && new String(u,StandardCharsets.UTF_8).equals("socksuser") && new String(p,StandardCharsets.UTF_8).equals("sockspass") && ver==5 && cmd==1 && rsv==0 && atyp==3 && new String(host,StandardCharsets.US_ASCII).equals("dns-only.example") && port==443 && java.util.Arrays.equals(data,MARKER);
                }
            });
            bridge.select(profile("socks5","127.0.0.1",upstream.getLocalPort(),"socksuser","sockspass"));
            try(Socket client=new Socket("127.0.0.1",bridge.getPort())) { client.setSoTimeout(5000); sendConnect(client,"dns-only.example:443"); assertTrue(status(client).startsWith("HTTP/1.1 200")); client.getOutputStream().write(MARKER); client.getOutputStream().flush(); byte[] echo=new byte[MARKER.length]; readFully(client.getInputStream(),echo); assertArrayEquals(MARKER,echo); }
            assertTrue(fixture.get(5,TimeUnit.SECONDS)); executor.shutdownNow();
        }
    }
}
