package com.leanvpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Invisible. It only exists because a few things need an activity: Android's VPN permission
 * dialog, opening the sign-in page, the "add tile" prompt, and the long-press menu.
 */
public final class Shim extends Activity {
    static final String SIGN_IN = "com.leanvpn.action.SIGN_IN";
    static final String CONNECT = "com.leanvpn.action.CONNECT";
    private static final String PAGE = "https://account.protonvpn.com/appletv";

    private boolean keepOpen;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String action = getIntent().getAction();
        if (SIGN_IN.equals(action) || CONNECT.equals(action)) withPermission();
        else if (TileService.ACTION_QS_TILE_PREFERENCES.equals(action)) menu();
        else offerTile();
    }

    /** VPN permission first (Android's own dialog, once), so the automatic connect after sign-in needs nothing. */
    private void withPermission() {
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            proceed();
            return;
        }
        try {
            startActivityForResult(consent, 1);
        } catch (ActivityNotFoundException e) {
            toast("This device doesn't allow VPN apps");
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != 1) return;
        if (result == RESULT_OK) {
            proceed();
        } else {
            toast("LeanVPN needs the VPN permission to connect");
            finish();
        }
    }

    private void proceed() {
        Engine e = Engine.get(this);
        Engine.State s = e.status().state;
        if (s != Engine.State.SIGNED_OUT && s != Engine.State.SIGNING_IN) {
            e.connect();
            finish();
            return;
        }
        if (s == Engine.State.SIGNED_OUT) toast("Getting a sign-in code…");
        e.signIn((code, error) -> {
            if (code != null) {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Proton sign-in code", code));
                toast("Code " + QuickTile.spaced(code) + " copied. Paste it on the page that opens.");
                if (!isDestroyed()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)));
                    } catch (ActivityNotFoundException ex) {
                        toast("Open " + PAGE + " and enter " + code);
                    }
                }
            } else if (error != null) {
                toast(error);
            }
            finish();
        });
    }

    /** Opening the app just asks Android to add the tile; after that it's the same as long-pressing it. */
    private void offerTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, QuickTile.class), "LeanVPN", Icon.createWithResource(this, R.drawable.tile),
                getMainExecutor(), result -> afterOffer());
        } else {
            toast("Add LeanVPN from the Quick Settings edit menu");
            afterOffer();
        }
    }

    private void afterOffer() {
        if (Engine.get(this).status().state == Engine.State.SIGNED_OUT) withPermission(); else menu();
    }

    private void menu() {
        Engine e = Engine.get(this);
        Engine.Status s = e.status();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("LeanVPN").setMessage(describe(s));
        switch (s.state) {
            case SIGNED_OUT:
                b.setPositiveButton("Sign in", (d, w) -> keep(this::withPermission));
                break;
            case SIGNING_IN:
                b.setPositiveButton("Open sign-in page", (d, w) -> keep(this::withPermission));
                b.setNegativeButton("Cancel sign-in", (d, w) -> e.signOut());
                break;
            case DISCONNECTED:
                b.setPositiveButton("Connect", (d, w) -> keep(this::withPermission));
                b.setNegativeButton("Sign out", (d, w) -> e.signOut());
                break;
            case CONNECTED:
                b.setPositiveButton("Disconnect", (d, w) -> e.disconnect());
                b.setNeutralButton("Switch server", (d, w) -> e.better());
                b.setNegativeButton("Sign out", (d, w) -> e.signOut());
                break;
            default:
                b.setPositiveButton("Disconnect", (d, w) -> e.disconnect());
                b.setNegativeButton("Sign out", (d, w) -> e.signOut());
        }
        b.setOnDismissListener(d -> { if (!keepOpen) finish(); });
        b.show();
    }

    private void keep(Runnable next) {
        keepOpen = true;
        next.run();
    }

    private static String describe(Engine.Status s) {
        switch (s.state) {
            case CONNECTED:
                return "Connected to " + s.server + (s.city != null ? " (" + s.city + ")" : "")
                    + "\n" + s.load + "% load" + (s.rtt >= 0 ? " · " + s.rtt + " ms" : "");
            case SIGNED_OUT: return s.message != null ? s.message : "Signed out";
            case SIGNING_IN: return s.code != null ? "Enter code " + QuickTile.spaced(s.code) + " at " + PAGE : "Signing in…";
            case DISCONNECTED: return s.message != null ? s.message : "Disconnected";
            default: return QuickTile.subtitle(s) + (s.message != null ? "\n" + s.message : "");
        }
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }
}
