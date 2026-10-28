package com.leanvpn;

import android.app.Application;
import com.wireguard.android.backend.GoBackend;

public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // Android's "Always-on VPN" starts the service directly (at boot, too): connect when it does.
        GoBackend.setAlwaysOnCallback(() -> Engine.get(this).connect());
    }
}
