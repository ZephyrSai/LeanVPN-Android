package com.leanvpn;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The whole interface: tap to connect or disconnect; the second line says where you are. */
public final class QuickTile extends TileService implements Engine.Listener {

    @Override public void onStartListening() {
        Engine e = Engine.get(this);
        e.setListener(this);
        onStatus(e.status());
        e.nudge();
    }

    @Override public void onStopListening() {
        Engine.get(this).setListener(null);
    }

    @Override public void onClick() {
        if (isLocked()) unlockAndRun(this::toggle); else toggle();
    }

    private void toggle() {
        Engine e = Engine.get(this);
        switch (e.status().state) {
            case SIGNED_OUT:
            case SIGNING_IN:
                open(Shim.SIGN_IN);
                break;
            case DISCONNECTED:
                if (VpnService.prepare(this) != null) {
                    open(Shim.CONNECT); // Android asks for the VPN permission first
                } else {
                    e.connect();
                    render(Tile.STATE_ACTIVE, "Connecting…");
                }
                break;
            default:
                e.disconnect();
                render(Tile.STATE_INACTIVE, "Off");
        }
    }

    @SuppressWarnings("deprecation")
    private void open(String action) {
        Intent i = new Intent(this, Shim.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    @Override public void onStatus(Engine.Status s) {
        render(s.on() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, subtitle(s));
    }

    private void render(int state, String subtitle) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(state);
        t.setLabel("LeanVPN");
        t.setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= 30) t.setStateDescription(subtitle);
        t.updateTile();
    }

    static String subtitle(Engine.Status s) {
        switch (s.state) {
            case SIGNED_OUT: return "Tap to sign in";
            case SIGNING_IN: return s.code != null ? "Code " + spaced(s.code) : "Getting a code…";
            case DISCONNECTED: return s.message != null ? s.message : "Off";
            case CONNECTING: return s.server != null ? s.server + "…" : "Connecting…";
            case SWITCHING: return s.server != null ? "→ " + s.server : "Switching…";
            case CONNECTED: return s.load >= 0 ? s.server + " · " + s.load + "%" : s.server;
            case WAITING: return "Waiting for network";
            default: return "Retrying…";
        }
    }

    static String spaced(String code) {
        return code.length() == 8 ? code.substring(0, 4) + " " + code.substring(4) : code;
    }
}
