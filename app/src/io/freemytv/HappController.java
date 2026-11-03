package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Controls Happ Proxy Utility (su.happ.proxyutility) from Free My TV's home screen, without root or ADB.
 *
 * Happ exposes two IPC entry points that a third-party app can use:
 *  1. A broadcast:  su.happ.proxyutility.action.widget.click  →  su.happ.proxyutility/.receiver.WidgetProvider
 *     Toggles VPN on/off (the same thing the home-screen widget does).
 *  2. A deeplink:   happ://toggle_without_ui  (or connect_without_ui / disconnect_without_ui)
 *     Goes through DeeplinkInterceptorActivity and toggles/connects/disconnects without bringing Happ's UI to front.
 *
 * Both work without root, ADB or Shizuku: they are ordinary exported components with no permission requirement.
 * The broadcast is preferred (no activity switch, no flicker); the deeplink is the fallback.
 *
 * VPN status is detected via ConnectivityManager + NetworkCallback: a TRANSPORT_VPN network that is
 * VALIDATED (or at least present) means a VPN is active. We cannot tell which app owns it without system
 * permissions, so we report "VPN active" rather than "Happ connected" when the transport is up but Happ
 * hasn't confirmed.  When Happ is not installed, everything degrades gracefully.
 *
 * Happ does not expose a public API for listing servers, switching servers, or reading ping.
 * Those features are handled by deep-linking into Happ's own UI (openHapp()).
 */
final class HappController {
    private HappController() {}

    static final String PKG = "su.happ.proxyutility";
    static final String ACTION_WIDGET_CLICK = "su.happ.proxyutility.action.widget.click";
    static final String RECEIVER = "su.happ.proxyutility/.receiver.WidgetProvider";

    // Deeplink URIs (scheme "happ", authority = action).
    static final String DEEPLINK_TOGGLE = "happ://toggle_without_ui";
    static final String DEEPLINK_CONNECT = "happ://connect_without_ui";
    static final String DEEPLINK_DISCONNECT = "happ://disconnect_without_ui";

    /** VPN states reported to the UI. */
    static final int STATE_UNKNOWN = 0, STATE_DISCONNECTED = 1, STATE_CONNECTING = 2,
            STATE_CONNECTED = 3, STATE_ERROR = 4;

    private static volatile boolean installed;
    private static volatile int state = STATE_UNKNOWN;
    private static volatile long lastToggleAt;
    private static volatile boolean toggling;   // suppresses duplicate broadcasts during a toggle
    private static ConnectivityManager cm;
    private static NetworkCallback callback;
    private static Handler handler;
    private static Runnable settleTimer;

    /** Listener called on the main thread when VPN state changes. */
    interface Listener {
        void onHappStateChanged(int state);
    }
    private static Listener listener;

    /**
     * Starts monitoring VPN status. Call from the home screen's resume().
     * Uses ConnectivityManager.NetworkCallback to track TRANSPORT_VPN networks.
     */
    static void start(Context context) {
        if (!isInstalled(context)) {
            installed = false;
            state = STATE_DISCONNECTED;
            return;
        }
        installed = true;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (cm == null) cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (callback == null) {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build();
            callback = new NetworkCallback();
            try {
                cm.registerNetworkCallback(req, callback);
            } catch (SecurityException e) {
                // Should not happen (no special permission needed for TRANSPORT_VPN listening on API 26+)
            }
        }
        // Check current state immediately
        checkCurrent(context);
    }

    /** Stops monitoring. Call from the home screen's pause(). */
    static void stop(Context context) {
        if (callback != null && cm != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception e) {}
        }
        callback = null;
        if (settleTimer != null && handler != null) {
            handler.removeCallbacks(settleTimer);
            settleTimer = null;
        }
        toggling = false;
    }

    /** Whether Happ is installed on this TV. */
    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PKG, 0);
            installed = true;
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
            return false;
        }
    }

    /** Current VPN state (STATE_UNKNOWN until start() is called). */
    static int state() {
        return state;
    }

    /** Whether a toggle is in progress (suppresses duplicate broadcasts). */
    static boolean isToggling() {
        return toggling;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /**
     * Toggles VPN on/off via the widget-click broadcast (preferred) or deeplink (fallback).
     * Suppresses duplicate calls while a toggle is in progress (settles after 4 s).
     */
    static void toggle(Context context) {
        if (!isInstalled(context)) {
            openHapp(context);   // not installed: open Play Store / nothing — at least try to open Happ
            return;
        }
        if (toggling) return;   // already toggling: ignore
        toggling = true;
        lastToggleAt = SystemClock.elapsedRealtime();
        // Keep the button lit until Android actually reports that the VPN disappeared.
        if (state != STATE_CONNECTED) state = STATE_CONNECTING;
        notifyListener();

        // Try the broadcast first (no UI flicker).
        boolean sent = sendBroadcast(context);
        if (!sent) {
            // Fallback: deeplink.
            sendDeeplink(context, DEEPLINK_TOGGLE);
        }

        // Settle after 4 seconds: if the VPN network hasn't appeared/disappeared by then,
        // read the actual state from ConnectivityManager.
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (settleTimer != null) handler.removeCallbacks(settleTimer);
        settleTimer = () -> {
            toggling = false;
            checkCurrent(context);
        };
        handler.postDelayed(settleTimer, 4000);
    }

    /** Connects VPN (if disconnected). */
    static void connect(Context context) {
        if (!isInstalled(context)) { openHapp(context); return; }
        if (toggling) return;
        if (state == STATE_CONNECTED) return;
        toggling = true;
        lastToggleAt = SystemClock.elapsedRealtime();
        state = STATE_CONNECTING;
        notifyListener();

        boolean sent = sendBroadcast(context);
        if (!sent) sendDeeplink(context, DEEPLINK_CONNECT);

        scheduleSettle(context);
    }

    /** Disconnects VPN (if connected). */
    static void disconnect(Context context) {
        if (!isInstalled(context)) return;
        if (toggling) return;
        if (state == STATE_DISCONNECTED) return;
        toggling = true;
        lastToggleAt = SystemClock.elapsedRealtime();
        // Keep the connected indication until NetworkCallback confirms disconnection.
        notifyListener();

        boolean sent = sendBroadcast(context);
        if (!sent) sendDeeplink(context, DEEPLINK_DISCONNECT);

        scheduleSettle(context);
    }

    /**
     * Opens the Happ app (its MainActivity). Used for server selection, ping, and other
     * features that have no public API.
     */
    static void openHapp(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // Happ not installed — silently ignore
            }
        }
    }

    /**
     * Opens Happ's server selection screen. Happ does not expose a public deeplink for this,
     * so we open the main activity and let the user navigate.
     */
    static void openServerSelection(Context context) {
        openHapp(context);
    }

    // --- Internal ---

    /** Sends the widget-click broadcast to toggle VPN. Returns true if sent. */
    private static boolean sendBroadcast(Context context) {
        try {
            Intent intent = new Intent(ACTION_WIDGET_CLICK);
            intent.setComponent(ComponentName.unflattenFromString(RECEIVER));
            // Use sendBroadcast (not sendOrderedBroadcast): the widget receiver is a plain broadcast receiver.
            context.sendBroadcast(intent);
            return true;
        } catch (SecurityException e) {
            // Some Android versions may block explicit broadcasts to other apps' receivers.
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts the deeplink activity. */
    private static void sendDeeplink(Context context, String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Happ not installed or deeplink not handled
        }
    }

    private static void scheduleSettle(Context context) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (settleTimer != null) handler.removeCallbacks(settleTimer);
        settleTimer = () -> {
            toggling = false;
            checkCurrent(context);
        };
        handler.postDelayed(settleTimer, 4000);
    }

    /** Reads the current VPN state from ConnectivityManager. */
    private static void checkCurrent(Context context) {
        if (cm == null) cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        boolean vpn = false;
        try {
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                }
            }
            // Also check all networks (VPN may not be the "active" one during handshake)
            if (!vpn) {
                Network[] all = cm.getAllNetworks();
                if (all != null) {
                    for (Network n : all) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            vpn = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // SecurityException or NullPointerException on some firmwares
        }
        int newState = vpn ? STATE_CONNECTED : STATE_DISCONNECTED;
        if (newState != state) {
            state = newState;
            notifyListener();
        }
    }

    private static void notifyListener() {
        if (listener != null) {
            handler.post(() -> listener.onHappStateChanged(state));
        }
    }

    /** NetworkCallback that tracks VPN transport changes. */
    private static class NetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override public void onAvailable(Network network) {
            super.onAvailable(network);
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                state = STATE_CONNECTED;
                notifyListener();
            });
        }

        @Override public void onLost(Network network) {
            super.onLost(network);
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                if (toggling) {
                    // VPN disappeared while toggling: it's disconnecting
                    state = STATE_DISCONNECTED;
                } else {
                    state = STATE_DISCONNECTED;
                }
                notifyListener();
            });
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            super.onCapabilitiesChanged(network, nc);
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                if (handler == null) handler = new Handler(Looper.getMainLooper());
                boolean validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                handler.post(() -> {
                    if (validated && state != STATE_CONNECTED) {
                        state = STATE_CONNECTED;
                        notifyListener();
                    }
                });
            }
        }
    }
}
