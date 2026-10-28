package com.leanvpn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Proton's server list reduced to what's needed, and the choice of the best one right now. */
final class Servers {
    private Servers() {}

    static final class Server {
        final String id, name, city, entry, key;
        final int tier, features;
        int load, status;
        double score;

        Server(String id, String name, String city, String entry, String key, int tier, int features,
               int load, int status, double score) {
            this.id = id; this.name = name; this.city = city; this.entry = entry; this.key = key;
            this.tier = tier; this.features = features; this.load = load; this.status = status; this.score = score;
        }

        boolean ipv6() { return (features & 16) != 0; }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("i", id).put("n", name).put("c", city).put("e", entry).put("k", key)
                .put("t", tier).put("f", features).put("l", load).put("s", status).put("sc", score);
        }

        static Server fromJson(JSONObject o) {
            return new Server(o.optString("i"), o.optString("n"), o.optString("c", null), o.optString("e"),
                o.optString("k"), o.optInt("t"), o.optInt("f"), o.optInt("l", 100), o.optInt("s"), o.optDouble("sc", 1e9));
        }
    }

    static final class Pick {
        final Server server;
        final int rtt; // ms, or -1 when not measured
        Pick(Server server, int rtt) { this.server = server; this.rtt = rtt; }
    }

    /** Keeps plain servers only: no Secure Core (1), Tor (2), restricted (32) or business gateways. */
    static List<Server> parse(String json) throws JSONException {
        JSONArray logicals = new JSONObject(json).getJSONArray("LogicalServers");
        List<Server> out = new ArrayList<>(logicals.length());
        for (int i = 0; i < logicals.length(); i++) {
            JSONObject l = logicals.getJSONObject(i);
            int features = l.optInt("Features");
            if ((features & (1 | 2 | 32)) != 0 || !l.isNull("GatewayName")) continue;
            JSONArray eps = l.optJSONArray("Servers");
            if (eps == null) continue;
            for (int j = 0; j < eps.length(); j++) {
                JSONObject ep = eps.getJSONObject(j);
                String key = ep.optString("X25519PublicKey", "");
                if (ep.optInt("Status") != 1 || key.isEmpty()) continue;
                JSONObject wg = ep.optJSONObject("EntryPerProtocol");
                wg = wg == null ? null : wg.optJSONObject("WireGuardUDP");
                String entry = wg != null && wg.has("IPv4") ? wg.optString("IPv4") : ep.optString("EntryIP", "");
                if (entry.isEmpty()) break;
                out.add(new Server(l.getString("ID"), l.getString("Name"), l.optString("City", null), entry, key,
                    l.optInt("Tier"), features, l.optInt("Load", 100), l.optInt("Status"), l.optDouble("Score", 1e9)));
                break;
            }
        }
        return out;
    }

    /**
     * The "freest" server that is also genuinely fast from here:
     * 1. Shortlist the 10 best by Proton's score (distance + load from your location), plus the
     *    6 least-loaded of the 60 nearest, so a quiet server close by gets a chance.
     * 2. Measure real latency to each (TCP connect on 443, outside the tunnel, all at once).
     * 3. cost = rtt_ms + 300 × load²: load barely matters when low and dominates near saturation.
     */
    static List<Pick> rank(List<Server> all, int maxTier, Set<String> avoid) {
        List<Server> usable = new ArrayList<>();
        for (Server s : all) if (s.tier <= maxTier && s.status == 1) usable.add(s);
        List<Server> pool = new ArrayList<>();
        for (Server s : usable) if (!avoid.contains(s.id)) pool.add(s);
        if (pool.isEmpty()) pool = usable;
        Collections.sort(pool, (a, b) -> Double.compare(a.score, b.score));
        List<Server> near = pool.subList(0, Math.min(60, pool.size()));
        List<Server> shortlist = new ArrayList<>(near.subList(0, Math.min(10, near.size())));
        List<Server> quiet = new ArrayList<>();
        for (Server s : near) if (s.load < 60) quiet.add(s);
        Collections.sort(quiet, (a, b) -> Integer.compare(a.load, b.load));
        for (Server s : quiet.subList(0, Math.min(6, quiet.size()))) if (!shortlist.contains(s)) shortlist.add(s);

        List<String> ips = new ArrayList<>();
        for (Server s : shortlist) ips.add(s.entry);
        int[] rtts = probe(ips, 443, 1500);
        List<Pick> picks = new ArrayList<>();
        for (int i = 0; i < shortlist.size(); i++) if (rtts[i] >= 0) picks.add(new Pick(shortlist.get(i), rtts[i]));
        if (picks.size() >= 3) {
            Collections.sort(picks, Comparator.comparingDouble(p -> p.rtt + penalty(p.server)));
            return picks;
        }
        // Probes blocked (port 443 filtered?): rank on Proton's score with the same load penalty.
        picks.clear();
        for (Server s : shortlist) picks.add(new Pick(s, -1));
        Collections.sort(picks, Comparator.comparingDouble(p -> p.server.score * 10 + penalty(p.server)));
        return picks;
    }

    static double penalty(Server s) {
        double l = s.load / 100.0;
        return 300 * l * l;
    }

    /** Connects to all of them at once; once 3 have answered, stragglers get 250 ms. */
    static int[] probe(List<String> ips, int port, int timeoutMs) {
        int n = ips.size();
        int[] rtt = new int[n];
        long[] start = new long[n];
        Arrays.fill(rtt, -1);
        try (Selector sel = Selector.open()) {
            for (int i = 0; i < n; i++) {
                try {
                    SocketChannel ch = SocketChannel.open();
                    ch.configureBlocking(false);
                    Http.Bypass b = Http.bypass;
                    if (b != null) b.apply(ch.socket());
                    start[i] = System.nanoTime();
                    if (ch.connect(new InetSocketAddress(InetAddress.getByName(ips.get(i)), port))) {
                        rtt[i] = 0;
                        ch.close();
                    } else {
                        ch.register(sel, SelectionKey.OP_CONNECT, i);
                    }
                } catch (IOException ignored) {
                }
            }
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            int answered = 0;
            while (!sel.keys().isEmpty()) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                if (sel.select(left) == 0) continue;
                for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext(); ) {
                    SelectionKey k = it.next();
                    it.remove();
                    int i = (Integer) k.attachment();
                    try {
                        if (((SocketChannel) k.channel()).finishConnect()) {
                            rtt[i] = (int) ((System.nanoTime() - start[i]) / 1_000_000L);
                            if (++answered == 3) deadline = Math.min(deadline, System.nanoTime() + 250_000_000L);
                        }
                    } catch (IOException ignored) {
                    }
                    k.cancel();
                    try { k.channel().close(); } catch (IOException ignored) { }
                }
            }
            for (SelectionKey k : sel.keys()) {
                try { k.channel().close(); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) {
        }
        return rtt;
    }

    /** "NL-FREE#93 129ms 54%", best first: enough to see from the log why a server won. */
    static String describe(List<Pick> ranked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            Pick p = ranked.get(i);
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.server.name).append(' ').append(p.rtt >= 0 ? p.rtt + "ms" : "no answer").append(' ')
                .append(p.server.load).append('%');
        }
        return sb.toString();
    }
}
