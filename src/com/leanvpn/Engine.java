package com.leanvpn;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.util.Log;
import com.leanvpn.Servers.Pick;
import com.leanvpn.Servers.Server;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import com.wireguard.crypto.Key;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;

/**
 * Owns the session, the tunnel and the health loop. Everything runs on one background thread,
 * so engine state needs no locking; the tile and the helper activity read {@link #status()}
 * and post commands.
 */
final class Engine {
    static final String TAG = "LeanVPN";

    enum State { SIGNED_OUT, SIGNING_IN, DISCONNECTED, CONNECTING, SWITCHING, CONNECTED, WAITING, FAILED }

    static final class Status {
        final State state;
        final String message, server, city, code;
        final int load, rtt;

        Status(State state, String message) { this(state, message, null, null, -1, -1, null); }

        Status(State state, String message, String server, String city, int load, int rtt, String code) {
            this.state = state; this.message = message; this.server = server; this.city = city;
            this.load = load; this.rtt = rtt; this.code = code;
        }

        /** The tile shows "on" whenever LeanVPN is trying to keep you connected. */
        boolean on() {
            return state == State.CONNECTING || state == State.SWITCHING || state == State.CONNECTED
                || state == State.WAITING || state == State.FAILED;
        }
    }

    interface Listener { void onStatus(Status s); }
    interface CodeCallback { void onCode(String code, String error); }

    private static Engine instance;

    static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Api api = new Api();
    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "leanvpn"; }
        @Override public void onStateChange(Tunnel.State s) {
            if (s == Tunnel.State.DOWN && !intentionalDown) h.post(() -> stopped("tunnel went down"));
        }
    };
    private final Map<String, Long> failed = new HashMap<>();
    private final Runnable tick = this::tick, poll = this::poll;
    private final Runnable retry = () -> { if (this.wantConnected && this.status.state == State.FAILED) doConnect(); };

    private GoBackend backend;
    private ServiceConnection binding;
    private List<Server> servers;
    private long loadsFetched;
    private Server current;
    private boolean up;
    private int ticks, stallTicks;
    private long lastRx, lastTx, retryDelay = 15_000;
    private String selector, code;
    private final Set<Network> networks = new HashSet<>();

    private volatile boolean intentionalDown, wantConnected, online = true;
    private volatile Status status;
    private volatile Listener listener;

    private Engine(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE);
        HandlerThread thread = new HandlerThread("engine");
        thread.start();
        h = new Handler(thread.getLooper());
        String uid = prefs.getString("uid", null);
        if (uid != null) api.session = new Api.Session(uid, prefs.getString("access", ""), prefs.getString("refresh", ""));
        api.listener = this::sessionChanged;
        wantConnected = prefs.getBoolean("want", false);
        status = new Status(api.session == null ? State.SIGNED_OUT : State.DISCONNECTED, null);
        if (uid == null && prefs.getLong("signInUntil", 0) > System.currentTimeMillis()) { // Android closed us mid sign-in
            selector = prefs.getString("signInSelector", null);
            code = prefs.getString("signInCode", null);
            api.session = new Api.Session(prefs.getString("signInUid", ""), prefs.getString("signInAccess", ""),
                prefs.getString("signInRefresh", ""));
            status = new Status(State.SIGNING_IN, null, null, null, -1, -1, code);
            h.post(poll);
        }
        // Our own API calls and probes go around the tunnel, so a dead tunnel never cuts us off.
        Http.bypass = s -> { Service v = Service.current; if (v != null) v.protect(s); };
        h.post(this::watchNetwork);
        if (api.session != null && wantConnected) connect();
    }

    Status status() { return status; }

    void setListener(Listener l) { listener = l; }

    // Commands (any thread)

    void connect() { wantConnected = true; h.post(this::doConnect); }

    void disconnect() { wantConnected = false; h.post(this::doDisconnect); }

    void better() {
        h.post(() -> { if (status.state == State.CONNECTED) move("Switching to the next best server…", false, 0); });
    }

    void signIn(CodeCallback cb) { h.post(() -> doSignIn(cb)); }

    void signOut() { wantConnected = false; h.post(this::doSignOut); }

    /** Quick Settings opened. Android may have frozen the app in the background (OnePlus does so
     *  quickly), so finish a pending sign-in or a failed connect now instead of on the old timer. */
    void nudge() {
        h.post(() -> {
            if (selector != null) {
                h.removeCallbacks(poll);
                poll();
            } else if (wantConnected && !up && status.state == State.FAILED) {
                retryDelay = 15_000;
                doConnect();
            }
        });
    }

    /** Android revoked the VPN (another VPN app started, or it was turned off in Settings). */
    void revoked() { h.post(() -> stopped("revoked")); }

    // Account

    private void doSignIn(CodeCallback cb) {
        if (selector == null && prefs.contains("uid")) { deliver(cb, null, null); return; } // already signed in
        if (code != null) { deliver(cb, code, null); return; }                          // reopen the page
        setStatus(new Status(State.SIGNING_IN, "Getting a sign-in code…"));
        try {
            String[] sc = api.startSignIn();
            selector = sc[0];
            code = sc[1];
            // Saved, so the sign-in survives Android closing the app while you're on the web page.
            prefs.edit().putString("signInSelector", selector).putString("signInCode", code)
                .putString("signInUid", api.session.uid).putString("signInAccess", api.session.access)
                .putString("signInRefresh", api.session.refresh)
                .putLong("signInUntil", System.currentTimeMillis() + 600_000).apply(); // codes last ~10 min
            setStatus(new Status(State.SIGNING_IN, "Enter the code at account.protonvpn.com/appletv", null, null, -1, -1, code));
            deliver(cb, code, null);
            h.postDelayed(poll, 5_000);
        } catch (Exception e) {
            Log.w(TAG, "sign-in failed: " + e);
            api.session = null;
            setStatus(new Status(State.SIGNED_OUT, "Sign-in failed: " + message(e)));
            deliver(cb, null, "Sign-in failed: " + message(e));
        }
    }

    private void poll() {
        if (selector == null) return;
        try {
            Api.Session s = api.finishSignIn(selector);
            if (s != null) {
                Log.i(TAG, "signed in");
                selector = null;
                code = null;
                prefs.edit().clear().putString("uid", s.uid).putString("access", s.access).putString("refresh", s.refresh).apply();
                api.session = s;
                servers = new ArrayList<>();
                loadsFetched = 0;
                app.deleteFile("servers.json");
                setStatus(new Status(State.DISCONNECTED, null));
                connect();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "sign-in check failed: " + e);
        }
        if (System.currentTimeMillis() < prefs.getLong("signInUntil", 0)) {
            h.postDelayed(poll, 5_000);
        } else {
            selector = null;
            code = null;
            api.session = null;
            prefs.edit().remove("signInSelector").remove("signInCode").remove("signInUid").remove("signInAccess")
                .remove("signInRefresh").remove("signInUntil").apply();
            setStatus(new Status(State.SIGNED_OUT, "Sign-in code expired"));
        }
    }

    private void doSignOut() {
        h.removeCallbacks(retry);
        h.removeCallbacks(poll);
        boolean signedIn = selector == null && prefs.contains("uid");
        selector = null;
        code = null;
        teardown();
        if (signedIn) api.signOut();
        api.session = null;
        prefs.edit().clear().apply();
        servers = new ArrayList<>();
        app.deleteFile("servers.json");
        setStatus(new Status(State.SIGNED_OUT, null));
    }

    private void sessionChanged(Api.Session s) {
        if (selector != null || !prefs.contains("uid")) return; // anonymous sign-in session: never saved
        if (s != null) {
            prefs.edit().putString("access", s.access).putString("refresh", s.refresh).apply();
            return;
        }
        wantConnected = false;
        teardown();
        prefs.edit().clear().apply();
        setStatus(new Status(State.SIGNED_OUT, "Session expired. Tap to sign in again."));
    }

    // Connecting

    private void doConnect() {
        if (api.session == null || selector != null) { if (selector == null) setStatus(new Status(State.SIGNED_OUT, null)); return; }
        if (!wantConnected) return;
        if (VpnService.prepare(app) != null) { // permission never granted, or another VPN app took over
            wantConnected = false;
            prefs.edit().putBoolean("want", false).apply();
            setStatus(new Status(State.DISCONNECTED, "Tap to allow the VPN"));
            return;
        }
        prefs.edit().putBoolean("want", true).apply();
        State s = status.state;
        if (s == State.CONNECTED || s == State.CONNECTING || s == State.SWITCHING || s == State.WAITING) return;
        h.removeCallbacks(retry);
        setStatus(new Status(State.CONNECTING, "Finding the best server…"));
        try {
            prepare();
            if (!wantConnected) return;
            List<Pick> ranked = Servers.rank(servers(), prefs.getInt("maxTier", 0), avoid());
            Log.i(TAG, "candidates: " + Servers.describe(ranked));
            bringUp(ranked, State.CONNECTING);
        } catch (Exception e) {
            if (!wantConnected || api.session == null) return;
            Log.w(TAG, "connect failed: " + e);
            fail(e);
        }
    }

    private void doDisconnect() {
        Log.i(TAG, "disconnect requested");
        prefs.edit().putBoolean("want", false).apply();
        h.removeCallbacks(retry);
        teardown();
        if (api.session != null && selector == null) setStatus(new Status(State.DISCONNECTED, null));
    }

    /** Key, certificate, account tier and a fresh enough server list. When Proton's API can't be
     *  reached, a still-valid certificate and the cached server list are enough to connect. */
    private void prepare() throws Exception {
        if (!prefs.contains("seed")) {
            prefs.edit().putString("seed", Base64.getEncoder().encodeToString(Keys.newSeed()))
                .remove("certExpiry").remove("certRefresh").apply();
        }
        long now = System.currentTimeMillis();
        if (prefs.getLong("certRefresh", 0) < now || prefs.getLong("certExpiry", 0) < now) {
            try {
                refreshCertificate();
            } catch (Exception e) {
                if (prefs.getLong("certExpiry", 0) <= System.currentTimeMillis()) throw e;
                Log.w(TAG, "certificate renewal failed, current one still valid: " + e);
            }
        }
        try {
            refreshServers();
        } catch (Exception e) {
            if (servers().isEmpty()) throw e;
            Log.w(TAG, "server refresh failed, using the cached list: " + e);
        }
    }

    private void refreshCertificate() throws Exception {
        try {
            long[] cert = api.certificate(Keys.publicPem(seed()), Build.MODEL, prefs.contains("certExpiry"));
            prefs.edit().putLong("certExpiry", cert[0]).putLong("certRefresh", cert[1]).apply();
        } catch (Exception e) {
            prefs.edit().putLong("certRefresh", System.currentTimeMillis() + 300_000).apply(); // not every tick
            throw e;
        }
    }

    /** Full list every 6 h (cheap 304 when unchanged), otherwise just loads every 10 min. */
    private void refreshServers() throws Exception {
        if (!up) { // scores are distances from where you are, so re-detect that on every connect
            try {
                String zone = netzone(api.publicIp());
                if (zone != null && !zone.equals(prefs.getString("netzone", null))) {
                    prefs.edit().putString("netzone", zone).apply();
                    loadsFetched = 0;
                }
            } catch (Exception ignored) {
            }
        }
        String zone = prefs.getString("netzone", null);
        List<Server> list = servers();
        if (list.isEmpty() || prefs.getLong("fetched", 0) < System.currentTimeMillis() - 6 * 3600_000L) {
            int tier = api.maxTier();
            SharedPreferences.Editor e = prefs.edit().putInt("maxTier", tier);
            try {
                int[] ports = api.wireGuardPorts();
                StringBuilder sb = new StringBuilder();
                for (int p : ports) sb.append(sb.length() > 0 ? "," : "").append(p);
                if (ports.length > 0) e.putString("ports", sb.toString());
            } catch (Exception ignored) {
            }
            String[] fresh = api.servers(tier == 0, zone, list.isEmpty() ? null : prefs.getString("modified", null));
            e.putLong("fetched", System.currentTimeMillis()).apply();
            if (fresh != null) {
                servers = Servers.parse(fresh[0]);
                prefs.edit().putString("modified", fresh[1]).apply();
                saveServers();
                loadsFetched = System.currentTimeMillis();
                return;
            }
        }
        if (loadsFetched < System.currentTimeMillis() - 600_000) {
            Map<String, Double> scores = new HashMap<>();
            Map<String, int[]> loads = api.loads(zone, scores);
            for (Server s : servers()) {
                int[] l = loads.get(s.id);
                if (l == null) continue;
                s.load = l[0];
                s.status = l[1];
                Double score = scores.get(s.id);
                if (score != null) s.score = score;
            }
            loadsFetched = System.currentTimeMillis();
        }
    }

    /** Tries ranked servers until one passes traffic: two per port, starting with the port that worked last time. */
    private void bringUp(List<Pick> ranked, State as) throws Exception {
        if (!online) throw new IOException("No network connection");
        if (ranked.isEmpty()) throw new IOException("No servers available");
        List<Integer> ports = new ArrayList<>();
        ports.add(prefs.getInt("goodPort", 51820));
        for (String p : prefs.getString("ports", "").split(",")) {
            if (!p.isEmpty() && !ports.contains(Integer.valueOf(p))) ports.add(Integer.valueOf(p));
        }
        bindVpn();
        for (int i = 0; i < Math.min(4, ranked.size()); i++) {
            if (!wantConnected) return;
            Pick pick = ranked.get(i);
            int port = ports.get((i / 2) % ports.size());
            setStatus(new Status(as, status.message, pick.server.name, pick.server.city, -1, -1, null));
            intentionalDown = true; // switching servers takes the old tunnel down first
            try {
                backend().setState(tunnel, Tunnel.State.UP, config(pick.server, port));
            } finally {
                intentionalDown = false;
            }
            up = true;
            // A handshake alone doesn't prove the server carries traffic (an overloaded one may not),
            // so also require an answer from the in-tunnel resolver.
            boolean ok = waitForHandshake() && trafficFlows();
            if (!wantConnected) return;
            if (ok) {
                if (port != prefs.getInt("goodPort", 51820)) prefs.edit().putInt("goodPort", port).apply();
                connected(pick);
                return;
            }
            failed.put(pick.server.id, System.currentTimeMillis());
            Log.i(TAG, pick.server.name + " passed no traffic on port " + port);
        }
        throw new IOException("No server answered");
    }

    private Config config(Server s, int port) throws Exception {
        boolean v6 = s.ipv6();
        Interface iface = new Interface.Builder()
            .parseAddresses(v6 ? "10.2.0.2/32, 2a07:b944::2:2/128" : "10.2.0.2/32")
            .parseDnsServers(v6 ? "10.2.0.1, 2a07:b944::2:1" : "10.2.0.1")
            .parsePrivateKey(Keys.wireGuardPrivateKey(seed()))
            .setMtu(1375)
            .build();
        // ::/0 too: on servers without IPv6 it is routed in and dropped instead of leaking around the tunnel.
        Peer peer = new Peer.Builder()
            .parsePublicKey(s.key)
            .parseEndpoint(s.entry + ":" + port)
            .parseAllowedIPs("0.0.0.0/0, ::/0")
            .setPersistentKeepalive(60)
            .build();
        return new Config.Builder().setInterface(iface).addPeer(peer).build();
    }

    private boolean waitForHandshake() {
        for (int i = 0; i < 24; i++) { // 6 s
            try { Thread.sleep(250); } catch (InterruptedException e) { return false; }
            if (!wantConnected) return false;
            if (lastHandshake() > 0) return true;
        }
        return false;
    }

    private void connected(Pick pick) {
        Log.i(TAG, "connected to " + pick.server.name + " load " + pick.server.load + " rtt " + pick.rtt + " ms");
        current = pick.server;
        retryDelay = 15_000;
        long[] t = traffic();
        lastRx = t[0];
        lastTx = t[1];
        stallTicks = 0;
        setStatus(new Status(State.CONNECTED, null, pick.server.name, pick.server.city, pick.server.load, pick.rtt, null));
        h.removeCallbacks(tick);
        h.postDelayed(tick, 10_000);
    }

    private void teardown() {
        h.removeCallbacks(tick);
        if (backend != null && (up || backend.getState(tunnel) == Tunnel.State.UP)) {
            intentionalDown = true;
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
            } catch (Exception e) {
                Log.w(TAG, "stop failed: " + e);
            } finally {
                intentionalDown = false;
            }
        }
        up = false;
        current = null;
        unbindVpn();
    }

    /** No kill switch, so never leave traffic routed into a tunnel that isn't working: take it
     *  down and retry with backoff (or as soon as a network comes back). */
    private void fail(Exception e) {
        teardown();
        setStatus(new Status(State.FAILED, message(e)));
        h.removeCallbacks(retry);
        h.postDelayed(retry, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 300_000);
    }

    private void stopped(String why) {
        if (!up && !wantConnected) return;
        Log.i(TAG, "VPN stopped: " + why);
        wantConnected = false;
        prefs.edit().putBoolean("want", false).apply();
        h.removeCallbacks(retry);
        teardown();
        if (api.session != null && selector == null) setStatus(new Status(State.DISCONNECTED, null));
    }

    // Staying connected

    /** Every 10 s while awake (it never wakes the phone): a cheap counter check; a probe only when traffic looks stuck. */
    private void tick() {
        if (!up || current == null) return;
        h.postDelayed(tick, 10_000);
        ticks++;
        if (!online) {
            if (status.state != State.WAITING) setStatus(with(State.WAITING, "Waiting for network"));
            return;
        }
        if (status.state == State.WAITING) setStatus(with(State.CONNECTED, null));
        long[] t = traffic();
        long rx = t[0] - lastRx, tx = t[1] - lastTx;
        lastRx = t[0];
        lastTx = t[1];
        if (rx > 0) stallTicks = 0; else if (tx > 1500) stallTicks++; // sending, nothing coming back
        long handshake = lastHandshake();
        if (stallTicks >= 2 || handshake == 0 || System.currentTimeMillis() - handshake > 200_000) {
            stallTicks = 0;
            if (!trafficFlows()) {
                move("Server stopped responding, switching…", true, 0);
                return;
            }
        } else if (ticks % 90 == 0) {
            checkLoad(); // every 15 min
        }
        if (up && prefs.getLong("certRefresh", 0) < System.currentTimeMillis()) {
            try { refreshCertificate(); } catch (Exception e) { Log.w(TAG, "certificate renewal failed: " + e); }
        }
    }

    /** Leaves a server that is entering maintenance or nearly full, if a clearly better one exists. */
    private void checkLoad() {
        Server cur = current;
        try {
            refreshServers();
        } catch (Exception e) {
            Log.w(TAG, "load refresh failed: " + e);
            return;
        }
        if (!up || current != cur) return;
        Server now = null;
        for (Server s : servers()) if (s.id.equals(cur.id)) now = s;
        if (now == null) return;
        current = now;
        if (status.state == State.CONNECTED && status.load != now.load) {
            setStatus(new Status(State.CONNECTED, null, now.name, now.city, now.load, status.rtt, null));
        }
        if (now.status == 0) move("Server going into maintenance, switching…", true, 0);
        else if (now.load >= 85) move("Server is " + now.load + "% full, switching…", false, now.load - 20);
    }

    private void move(String reason, boolean markFailed, int onlyIfLoadBelow) {
        Server cur = current;
        if (cur == null || !up) return;
        if (markFailed) failed.put(cur.id, System.currentTimeMillis());
        int curRtt = status.rtt;
        Set<String> avoid = avoid();
        avoid.add(cur.id);
        List<Pick> ranked = Servers.rank(servers(), prefs.getInt("maxTier", 0), avoid);
        if (onlyIfLoadBelow > 0 && (ranked.isEmpty() || ranked.get(0).server.load >= onlyIfLoadBelow)) return;
        Log.i(TAG, "leaving " + cur.name + ": " + reason + "; candidates: " + Servers.describe(ranked));
        setStatus(new Status(State.SWITCHING, reason));
        try {
            bringUp(ranked, State.SWITCHING);
        } catch (Exception e) {
            if (!wantConnected) return;
            Log.w(TAG, "switch failed: " + e);
            if (!markFailed) { // a voluntary move left a server that still worked: go back to it
                try {
                    bringUp(Collections.singletonList(new Pick(cur, curRtt)), State.SWITCHING);
                    if (status.state == State.CONNECTED) return;
                } catch (Exception ignored) {
                }
            }
            fail(e);
        }
    }

    /** Sends one DNS query to the in-tunnel resolver (twice); any answer proves traffic flows both ways. */
    private static boolean trafficFlows() {
        return dnsProbe() || dnsProbe();
    }

    private static boolean dnsProbe() {
        byte[] q = {0x4c, 0x56, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            9, 'p', 'r', 'o', 't', 'o', 'n', 'v', 'p', 'n', 3, 'c', 'o', 'm', 0, 0x00, 0x01, 0x00, 0x01};
        try (DatagramSocket s = new DatagramSocket()) { // not bypassed: this one must go through the tunnel
            s.setSoTimeout(2500);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByAddress(new byte[] {10, 2, 0, 1}), 53));
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return p.getLength() >= 12 && buf[0] == 0x4c && buf[1] == 0x56;
        } catch (IOException e) {
            return false;
        }
    }

    private long lastHandshake() {
        try {
            Statistics st = backend.getStatistics(tunnel);
            for (Key k : st.peers()) {
                Statistics.PeerStats ps = st.peer(k);
                if (ps != null) return ps.latestHandshakeEpochMillis();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private long[] traffic() {
        try {
            Statistics st = backend.getStatistics(tunnel);
            return new long[] {st.totalRx(), st.totalTx()};
        } catch (Exception e) {
            return new long[] {lastRx, lastTx};
        }
    }

    // Plumbing

    /** WireGuard's backend starts its VPN service; binding it instead keeps one service alive across
     *  server switches and avoids Android's limits on starting services from the background. */
    private GoBackend backend() {
        if (backend == null) {
            backend = new GoBackend(new ContextWrapper(app) {
                @Override public ComponentName startService(Intent service) {
                    bindVpn();
                    return new ComponentName(app, Service.class);
                }
            });
        }
        return backend;
    }

    private void bindVpn() {
        if (binding != null) return;
        binding = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        app.bindService(new Intent(app, Service.class), binding, Context.BIND_AUTO_CREATE);
    }

    private void unbindVpn() {
        if (binding == null) return;
        try { app.unbindService(binding); } catch (IllegalArgumentException ignored) { }
        binding = null;
    }

    private void watchNetwork() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
        cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { networks.add(n); setOnline(true); }
            @Override public void onLost(Network n) { networks.remove(n); setOnline(!networks.isEmpty()); }
        }, h);
    }

    private void setOnline(boolean now) {
        boolean was = online;
        online = now;
        // Back online after a failed attempt: retry now rather than waiting out the backoff.
        if (!was && now && wantConnected && !up && status.state == State.FAILED) {
            retryDelay = 15_000;
            doConnect();
        }
    }

    private void setStatus(Status s) {
        Status old = status;
        status = s;
        Listener l = listener;
        if (l != null) main.post(() -> l.onStatus(s));
        if (old.state != s.state || !Objects.equals(old.server, s.server) || !Objects.equals(old.code, s.code)
            || old.load != s.load) {
            try {
                TileService.requestListeningState(app, new ComponentName(app, QuickTile.class));
            } catch (RuntimeException ignored) { // tile not added
            }
        }
    }

    private Status with(State state, String message) {
        Status s = status;
        return new Status(state, message, s.server, s.city, s.load, s.rtt, null);
    }

    private void deliver(CodeCallback cb, String code, String error) {
        main.post(() -> cb.onCode(code, error));
    }

    private Set<String> avoid() {
        long cutoff = System.currentTimeMillis() - 1_800_000;
        for (Iterator<Long> it = failed.values().iterator(); it.hasNext(); ) if (it.next() < cutoff) it.remove();
        return new HashSet<>(failed.keySet());
    }

    private byte[] seed() {
        return Base64.getDecoder().decode(prefs.getString("seed", ""));
    }

    private List<Server> servers() {
        if (servers == null) {
            servers = new ArrayList<>();
            try (FileInputStream in = app.openFileInput("servers.json")) {
                byte[] data = new byte[(int) in.getChannel().size()];
                int off = 0;
                for (int n; off < data.length && (n = in.read(data, off, data.length - off)) > 0; ) off += n;
                JSONArray a = new JSONArray(new String(data, 0, off, StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) servers.add(Server.fromJson(a.getJSONObject(i)));
            } catch (Exception ignored) { // none cached yet
            }
        }
        return servers;
    }

    private void saveServers() {
        try (FileOutputStream out = app.openFileOutput("servers.json", Context.MODE_PRIVATE)) {
            JSONArray a = new JSONArray();
            for (Server s : servers) a.put(s.toJson());
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "couldn't cache servers: " + e);
        }
    }

    /** The API scores servers by distance from this zone (last octet dropped, as Proton's apps do). */
    private static String netzone(String ip) {
        if (ip == null) return null;
        String[] p = ip.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + "." + p[2] + ".0" : null;
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
