package com.cowbell.cordova.geofence;

import android.app.Activity;
import android.util.Log;

import org.apache.cordova.CordovaWebView;

import java.lang.ref.WeakReference;
import java.util.List;

public class GeofenceJsEvent {
    public static final String TAG = "GeofencePlugin";
    public static WeakReference<CordovaWebView> webView = null;

    public static void onTransitionReceived(List<GeoNotification> notifications) {
        Log.d(TAG, "Transition Event Received!");
        String js = "setTimeout('geofence.onTransitionReceived("
                + Gson.get().toJson(notifications) + ")',0)";
        sendJavascript(js);
    }

    public static void onNotificationClicked(String data) {
        if (data != null) {
            String js = "setTimeout('geofence.onNotificationClicked(" + data + ")', 100)";
            sendJavascript(js);
        }
    }

    public static void onPermissionsResult(boolean result){
        String js = "setTimeout('geofence.onPermissions(" + result + ")', 300)";
        sendJavascript(js);
    }

    private static synchronized void sendJavascript(final String js) {

        if (webView == null) {
            Log.e(TAG, "Device isn't ready.");
            return;
        }

        final CordovaWebView view = webView.get();

        ((Activity)(view.getContext())).runOnUiThread(new Runnable() {
            public void run() {
                try {
                    view.loadUrl("javascript:" + js);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to trigger javascript event, app might be destroyed!");
                    e.printStackTrace();
                }
            }
        });
    }
}
