package com.leanvpn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Just enough HTTPS for Proton's API, with two things the platform clients can't do:
 * every socket goes around the VPN (so a dead tunnel never cuts LeanVPN off from the API),
 * and the TLS ClientHello is sent in two pieces, because some networks silently drop it
 * when it arrives in one TCP segment.
 */
final class Http {
    private Http() {}

    /** Sends a socket around the tunnel; set by the VPN service while it runs. */
    interface Bypass { void apply(Socket s); }
    static volatile Bypass bypass;

    static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    private static final Map<String, InetAddress[]> resolved = new HashMap<>();
    private static final int[] SPLITS = {8, 1, 5};

    /**
     * Retries the quick failures this network produces, but only when the request can't have
     * reached the server (or is a GET): a replayed token refresh would end the session. Each try
     * splits the ClientHello at a different point, since filters differ in what they reset.
     */
    static Response send(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs)
            throws IOException {
        URI u = URI.create(url);
        String path = u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean[] wrote = {false};
            try {
                return once(method, u.getHost(), path, headers, body, timeoutMs, SPLITS[attempt], wrote);
            } catch (IOException e) {
                last = e;
                if (wrote[0] && !"GET".equals(method)) break;
            }
        }
        throw last;
    }

    private static Response once(String method, String host, String path, Map<String, String> headers,
                                 byte[] body, int timeoutMs, int split, boolean[] wrote) throws IOException {
        SocketChannel channel = SocketChannel.open();
        Socket raw = channel.socket();
        try {
            Bypass b = bypass;
            if (b != null) b.apply(raw);
            raw.connect(new InetSocketAddress(resolve(host), 443), 10_000);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(8_000); // handshake
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(new SplitSocket(raw, split), host, 443, true);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(params);
            tls.startHandshake();
            if (isAndroid() && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new SSLPeerUnverifiedException("Certificate is not for " + host);
            }
            tls.setSoTimeout(timeoutMs);

            StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\nAccept-Encoding: identity\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            if (body != null || !"GET".equals(method)) {
                head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = new BufferedOutputStream(tls.getOutputStream());
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            wrote[0] = true;
            out.flush();
            return read(new BufferedInputStream(tls.getInputStream()));
        } finally {
            try { channel.close(); } catch (IOException ignored) { }
        }
    }

    private static Response read(InputStream in) throws IOException {
        Response r = new Response();
        String status = line(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Bad response: " + status);
        r.status = Integer.parseInt(parts[1]);
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String length = r.header("content-length");
        if ("chunked".equalsIgnoreCase(r.header("transfer-encoding"))) {
            for (int size = Integer.parseInt(line(in).split(";")[0].trim(), 16); size > 0;
                 size = Integer.parseInt(line(in).split(";")[0].trim(), 16)) {
                copy(in, body, size);
                line(in);
            }
        } else if (length != null) {
            copy(in, body, Integer.parseInt(length));
        } else {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
        }
        r.body = body.toByteArray();
        return r;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new EOFException("Connection closed");
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("Header line too long");
        }
        return sb.toString();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int got = in.read(buf, 0, Math.min(buf.length, n));
            if (got < 0) throw new EOFException("Connection closed");
            out.write(buf, 0, got);
            n -= got;
        }
    }

    /** Remembers the last answer, so the API stays reachable while the tunnel's DNS is down. */
    private static InetAddress resolve(String host) throws UnknownHostException {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            synchronized (resolved) { resolved.put(host, all); }
            return all[0];
        } catch (UnknownHostException e) {
            synchronized (resolved) {
                InetAddress[] cached = resolved.get(host);
                if (cached != null) return cached[0];
            }
            throw e;
        }
    }

    private static boolean isAndroid() {
        return System.getProperty("java.vm.name", "").contains("Dalvik");
    }

    /**
     * Wraps a connected socket so its first write (the ClientHello) goes out as two segments.
     * It deliberately has no file descriptor: that makes the platform's TLS layer use this
     * socket's streams instead of writing to the descriptor directly.
     */
    static final class SplitSocket extends Socket {
        private final Socket s;
        private final int at;
        private OutputStream out;

        SplitSocket(Socket s, int at) throws SocketException {
            super(new NoDescriptor());
            this.s = s;
            this.at = at;
        }

        @Override public synchronized OutputStream getOutputStream() throws IOException {
            if (out == null) {
                final OutputStream o = s.getOutputStream();
                out = new FilterOutputStream(o) {
                    private boolean split;
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        if (split || len < 16) {
                            o.write(b, off, len);
                            return;
                        }
                        split = true;
                        o.write(b, off, at);
                        o.flush();
                        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        o.write(b, off + at, len - at);
                    }
                };
            }
            return out;
        }

        @Override public InputStream getInputStream() throws IOException { return s.getInputStream(); }
        @Override public boolean isConnected() { return s.isConnected(); }
        @Override public boolean isBound() { return s.isBound(); }
        @Override public boolean isClosed() { return s.isClosed(); }
        @Override public boolean isInputShutdown() { return s.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return s.isOutputShutdown(); }
        @Override public void close() throws IOException { s.close(); }
        @Override public void shutdownInput() throws IOException { s.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { s.shutdownOutput(); }
        @Override public InetAddress getInetAddress() { return s.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return s.getLocalAddress(); }
        @Override public int getPort() { return s.getPort(); }
        @Override public int getLocalPort() { return s.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return s.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return s.getLocalSocketAddress(); }
        @Override public void setSoTimeout(int t) throws SocketException { s.setSoTimeout(t); }
        @Override public int getSoTimeout() throws SocketException { return s.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean on) throws SocketException { s.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws SocketException { return s.getTcpNoDelay(); }
        @Override public void setKeepAlive(boolean on) throws SocketException { s.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws SocketException { return s.getKeepAlive(); }
        @Override public void setSoLinger(boolean on, int linger) throws SocketException { s.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws SocketException { return s.getSoLinger(); }
        @Override public void setSendBufferSize(int size) throws SocketException { s.setSendBufferSize(size); }
        @Override public int getSendBufferSize() throws SocketException { return s.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws SocketException { s.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() throws SocketException { return s.getReceiveBufferSize(); }
        @Override public void connect(SocketAddress a, int t) throws IOException { throw new SocketException("Already connected"); }
        @Override public void bind(SocketAddress a) throws IOException { throw new SocketException("Already bound"); }
        @Override public String toString() { return "SplitSocket[" + s + "]"; }

        private static final class NoDescriptor extends SocketImpl {
            @Override protected FileDescriptor getFileDescriptor() { throw new UnsupportedOperationException("no descriptor"); }
            @Override protected void create(boolean stream) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void connect(String host, int port) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void connect(InetAddress address, int port) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void connect(SocketAddress address, int timeout) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void bind(InetAddress host, int port) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void listen(int backlog) throws IOException { throw new SocketException("unsupported"); }
            @Override protected void accept(SocketImpl s) throws IOException { throw new SocketException("unsupported"); }
            @Override protected InputStream getInputStream() throws IOException { throw new SocketException("unsupported"); }
            @Override protected OutputStream getOutputStream() throws IOException { throw new SocketException("unsupported"); }
            @Override protected int available() { return 0; }
            @Override protected void close() { }
            @Override protected void sendUrgentData(int data) throws IOException { throw new SocketException("unsupported"); }
            @Override public void setOption(int optID, Object value) throws SocketException { throw new SocketException("unsupported"); }
            @Override public Object getOption(int optID) throws SocketException { throw new SocketException("unsupported"); }
        }
    }
}
