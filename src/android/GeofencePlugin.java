package com.cowbell.cordova.geofence;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.Manifest;
import com.google.gson.JsonParser;
import android.app.NotificationManager;


import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class GeofencePlugin extends CordovaPlugin {
    public static final String TAG = "GeofencePlugin";

    public static final String ERROR_UNKNOWN = "UNKNOWN";
    public static final String ERROR_PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String ERROR_GEOFENCE_NOT_AVAILABLE = "GEOFENCE_NOT_AVAILABLE";
    public static final String ERROR_GEOFENCE_LIMIT_EXCEEDED = "GEOFENCE_LIMIT_EXCEEDED";
    private String[] allPermissions = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };
    private static HashMap<String, Long> snoozedFences = new HashMap<>();

    private GeoNotificationManager geoNotificationManager;
    private Context context;
    protected GeoNotificationStore store;


    private class Action {
        public String action;
        public JSONArray args;
        public CallbackContext callbackContext;

        public Action(String action, JSONArray args, CallbackContext callbackContext) {
            this.action = action;
            this.args = args;
            this.callbackContext = callbackContext;
        }
    }

    //FIXME: what about many executedActions at once
    private Action executedAction;

    /**
     * @param cordova
     *            The context of the main Activity.
     * @param webView
     *            The associated CordovaWebView.
     */
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        GeofenceJsEvent.webView = new WeakReference<CordovaWebView>(webView);
        context = this.cordova.getActivity().getApplicationContext();
        Logger.setLogger(new Logger(TAG, context, false));
        geoNotificationManager = new GeoNotificationManager(context);
        store = new GeoNotificationStore(context);
    }

    @Override
    public void onNewIntent(Intent intent) {
        String data = intent.getStringExtra("geofence.notification.data");
        if (data != null) {
            GeofenceJsEvent.onNotificationClicked(data);
        }
    }

    @Override
    public boolean execute(final String action, final JSONArray args,
                           final CallbackContext callbackContext) throws JSONException {
        executedAction = new Action(action, args, callbackContext);
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                if (action.equals("addOrUpdate")) {
                    List<GeoNotification> geoNotifications = new ArrayList<GeoNotification>();
                    for (int i = 0; i < args.length(); i++) {
                        GeoNotification not = parseFromJSONObject(args.optJSONObject(i));
                        if (not != null) {
                            geoNotifications.add(not);
                        }
                    }
                    geoNotificationManager.addGeoNotifications(geoNotifications, callbackContext);
                } else if (action.equals("remove")) {
                    List<String> ids = new ArrayList<String>();
                    for (int i = 0; i < args.length(); i++) {
                        ids.add(args.optString(i));
                    }
                    geoNotificationManager.removeGeoNotifications(ids, callbackContext);
                } else if (action.equals("removeAll")) {
                    geoNotificationManager.removeAllGeoNotifications(callbackContext);
                } else if (action.equals("getWatched")) {
                    List<GeoNotification> geoNotifications = geoNotificationManager.getWatched();
                    callbackContext.success(Gson.get().toJson(geoNotifications));
                } else if (action.equals("dismissNotifications")) {
                    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    for (int i = 0; i < args.length(); i++) {
                        manager.cancel(args.optInt(i));
                    }
                } else if (action.equals("snooze")) {
                    snoozedFences.put(args.optString(0), System.currentTimeMillis() + args.optLong(1) * 1000);
                } else if (action.equals("initialize")) {
                    GeofenceConfig config = new GeofenceConfig();
                    config.delay = 10;
                    JSONObject jsonConfig = args.optJSONObject(0);
                    if (jsonConfig != null) {
                        config = parseConfig(jsonConfig);
                    }
                    store.setConfig(config);
                    initialize(callbackContext);
                } else if (action.equals("permissions")){
                    permissions(callbackContext);
                } else if (action.equals("hasPermissions")){
                    hasPermissions(callbackContext);
                } else if (action.equals("deviceReady")) {
                    Intent intent = cordova.getActivity().getIntent();
                    String data = intent.getStringExtra("geofence.notification.data");
                    if (data != null) {
                        GeofenceJsEvent.onNotificationClicked(data);
                    }
                }
            }
        });

        return true;
    }

    public boolean execute(Action action) throws JSONException {
        return execute(action.action, action.args, action.callbackContext);
    }

    private GeoNotification parseFromJSONObject(JSONObject object) {
        GeoNotification geo = GeoNotification.fromJson(object.toString());
        return geo;
    }

    private GeofenceConfig parseConfig (JSONObject json) {
        return Gson.get().fromJson(json.toString(), GeofenceConfig.class);
    }

    private void initialize(CallbackContext callbackContext) {
        String[] permissions = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[2] = Manifest.permission.ACCESS_BACKGROUND_LOCATION;
        }


        if (!permissionsGranted(permissions)) {
            PermissionHelper.requestPermissions(this, 0, permissions);
        } else {
            callbackContext.error("Permission not granted");
        }
    }

    private void hasPermissions(CallbackContext callbackContext) {
        if (permissionsGranted(allPermissions)) {
            callbackContext.success();
        } else {
            callbackContext.error("Permissions not granted");
        }
    }


    private void permissions(CallbackContext callbackContext) {
        PermissionHelper.requestPermissions(this, 0, allPermissions);
        callbackContext.success();
    }

    public static boolean isSnoozed(String id) {
        Long fenceTime = snoozedFences.get(id);
        return fenceTime != null && fenceTime > System.currentTimeMillis();
    }

    private boolean permissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (!PermissionHelper.hasPermission(this, permission)) {
                return false;
            }
        }

        return true;
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        PluginResult result;

        if (executedAction != null) {
            if (Objects.equals(executedAction.action, "permissions")) {
                boolean permissionsResult = permissionsGranted(allPermissions);
                GeofenceJsEvent.onPermissionsResult(permissionsResult);
                result = new PluginResult(PluginResult.Status.OK);
                executedAction.callbackContext.sendPluginResult(result);
                executedAction = null;
                return;
            }
            for (int r:grantResults) {
                if (r == PackageManager.PERMISSION_DENIED) {
                    Log.d(TAG, "Permission Denied!");
                    result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION);
                    executedAction.callbackContext.sendPluginResult(result);
                    executedAction = null;
                    return;
                }
            }
            Log.d(TAG, "Permission Granted!");
            if (!permissionsGranted(allPermissions)) {
                // Most likely ACCESS_BACKGROUND_LOCATION is missing, request again
                PermissionHelper.requestPermissions(this, 0, allPermissions);
            }
            execute(executedAction);
            executedAction = null;
        }
    }
}
