package com.leanvpn;

import com.wireguard.android.backend.GoBackend;

/** WireGuard's VPN service, owned by the app so its sockets can be sent around the tunnel. */
public final class Service extends GoBackend.VpnService {
    static volatile Service current;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
    }

    @Override public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Another VPN app started, or the VPN was turned off in Settings: stop, and don't fight it. */
    @Override public void onRevoke() {
        Engine.get(this).revoked();
    }
}
