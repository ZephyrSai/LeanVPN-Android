package com.leanvpn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The handful of Proton VPN API calls LeanVPN needs (endpoints and headers as in Proton's apps).
 * It signs in as Proton's Apple TV client, because the web page that accepts sign-in codes
 * approves them for that client only. Used from the engine thread only.
 */
final class Api {
    static final String BASE = "https://vpn-api.proton.me";
    private static final String APP_VERSION = "appletv-vpn@1.6.1";

    static final class Session {
        final String uid, access, refresh;
        Session(String uid, String access, String refresh) { this.uid = uid; this.access = access; this.refresh = refresh; }
    }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) { super(message.isEmpty() ? "HTTP " + status : message); this.status = status; }
        boolean sessionGone() { return status == 400 || status == 401 || status == 422; }
    }

    interface SessionListener { void onSession(Session s); } // new tokens, or null when the session is gone

    Session session;
    SessionListener listener = s -> { };

    // Sign-in: the "enter this code on the web" flow, so no password ever touches this app.

    /** Returns {selector, code}. */
    String[] startSignIn() throws IOException, JSONException {
        session = null;
        session = sessionFrom(call("POST", "/auth/v4/sessions", null, null, false));
        JSONObject j = json(call("GET", "/auth/v4/sessions/forks", null, null, false));
        return new String[] {j.getString("Selector"), j.getString("UserCode")};
    }

    /** Null until the user has entered the code. */
    Session finishSignIn(String selector) throws IOException, JSONException {
        try {
            return sessionFrom(call("GET", "/auth/v4/sessions/forks/" + selector, null, null, false));
        } catch (ApiException e) {
            if (e.status == 422) return null;
            throw e;
        }
    }

    void signOut() {
        try { call("DELETE", "/auth/v4", null, null, false); } catch (Exception ignored) { }
        session = null;
    }

    // VPN

    int maxTier() throws IOException, JSONException {
        JSONObject vpn = json(call("GET", "/vpn/v2", null, null, true)).optJSONObject("VPN");
        return vpn == null ? 0 : vpn.optInt("MaxTier");
    }

    /** WireGuard UDP ports the servers listen on (fallbacks for networks that block 51820). */
    int[] wireGuardPorts() throws IOException, JSONException {
        JSONObject ports = json(call("GET", "/vpn/v2/clientconfig", null, null, true)).optJSONObject("DefaultPorts");
        JSONObject wg = ports == null ? null : ports.optJSONObject("WireGuard");
        JSONArray udp = wg == null ? null : wg.optJSONArray("UDP");
        int[] out = new int[udp == null ? 0 : udp.length()];
        for (int i = 0; i < out.length; i++) out[i] = udp.getInt(i);
        return out;
    }

    String publicIp() throws IOException, JSONException {
        return json(call("GET", "/vpn/v1/location", null, null, true)).optString("IP", null);
    }

    /** Null when the list hasn't changed since {@code modifiedSince}; otherwise {raw JSON, Last-Modified}. */
    String[] servers(boolean freeOnly, String netzone, String modifiedSince) throws IOException {
        Map<String, String> h = new HashMap<>();
        if (netzone != null) h.put("x-pm-netzone", netzone);
        if (modifiedSince != null) h.put("If-Modified-Since", modifiedSince);
        Http.Response r = call("GET", "/vpn/v1/logicals?WithState=1&WithEntriesForProtocols=WireGuardUDP"
            + (freeOnly ? "&Tier=0" : ""), null, h, true);
        return r.status == 304 ? null : new String[] {r.text(), r.header("last-modified")};
    }

    /** id → {load, status}, plus scores in {@code scores}. */
    Map<String, int[]> loads(String netzone, Map<String, Double> scores) throws IOException, JSONException {
        Map<String, String> h = new HashMap<>();
        if (netzone != null) h.put("x-pm-netzone", netzone);
        JSONArray list = json(call("GET", "/vpn/v1/loads", null, h, true)).optJSONArray("LogicalServers");
        Map<String, int[]> out = new HashMap<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.getJSONObject(i);
            out.put(l.getString("ID"), new int[] {l.optInt("Load", 100), l.optInt("Status")});
            scores.put(l.getString("ID"), l.optDouble("Score", 1e9));
        }
        return out;
    }

    /** Registers our public key; the servers then accept the matching WireGuard key. Returns {expiry, refresh} in ms. */
    long[] certificate(String publicKeyPem, String deviceName, boolean renew) throws IOException, JSONException {
        JSONObject body = new JSONObject()
            .put("ClientPublicKey", publicKeyPem)
            .put("ClientPublicKeyMode", "EC")
            .put("DeviceName", deviceName)
            .put("Mode", "session")
            .put("Features", new JSONObject().put("NetShieldLevel", 0).put("SplitTCP", true).put("RandomNAT", true));
        if (renew) body.put("Renew", true);
        JSONObject j = json(call("POST", "/vpn/v1/certificate", body, null, true));
        return new long[] {j.getLong("ExpirationTime") * 1000, j.getLong("RefreshTime") * 1000};
    }

    // Plumbing

    private Http.Response call(String method, String path, JSONObject body, Map<String, String> extra, boolean retry)
            throws IOException {
        Session sent = session;
        Http.Response r = Http.send(method, BASE + path, headers(extra, body != null), body == null ? null
            : body.toString().getBytes(StandardCharsets.UTF_8), path.startsWith("/vpn/v1/logicals") ? 60_000 : 20_000);
        if (r.status == 401 && retry && session != null) {
            if (session == sent) refresh(); // unless another call already refreshed meanwhile
            return call(method, path, body, extra, false);
        }
        if (r.status >= 400) throw error(r);
        return r;
    }

    private void refresh() throws IOException {
        Session s = session;
        Map<String, String> h = headers(null, true);
        h.put("x-pm-uid", s.uid);
        h.remove("Authorization");
        byte[] body;
        try {
            body = new JSONObject().put("ResponseType", "token").put("GrantType", "refresh_token")
                .put("RefreshToken", s.refresh).put("RedirectURI", "http://protonmail.ch")
                .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Http.Response r = Http.send("POST", BASE + "/auth/refresh", h, body, 20_000);
        try {
            if (r.status < 400) {
                JSONObject j = new JSONObject(r.text());
                session = new Session(s.uid, j.getString("AccessToken"), j.getString("RefreshToken"));
                listener.onSession(session);
                return;
            }
        } catch (JSONException ignored) {
        }
        ApiException e = error(r);
        if (e.sessionGone()) {
            session = null;
            listener.onSession(null);
        }
        throw e;
    }

    private Map<String, String> headers(Map<String, String> extra, boolean json) {
        Map<String, String> h = new HashMap<>();
        h.put("x-pm-appversion", APP_VERSION);
        h.put("Accept", "application/vnd.protonmail.v1+json");
        h.put("User-Agent", "ProtonVPN/1.6.1 (tvOS)");
        if (json) h.put("Content-Type", "application/json");
        Session s = session;
        if (s != null) {
            h.put("x-pm-uid", s.uid);
            h.put("Authorization", "Bearer " + s.access);
        }
        if (extra != null) h.putAll(extra);
        return h;
    }

    private static Session sessionFrom(Http.Response r) throws JSONException {
        JSONObject j = json(r);
        return new Session(j.getString("UID"), j.getString("AccessToken"), j.getString("RefreshToken"));
    }

    private static JSONObject json(Http.Response r) throws JSONException {
        return new JSONObject(r.text());
    }

    private static ApiException error(Http.Response r) {
        String msg = "";
        try { msg = new JSONObject(r.text()).optString("Error", ""); } catch (JSONException ignored) { }
        return new ApiException(r.status, msg);
    }
}
