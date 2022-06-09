package com.cowbell.cordova.geofence;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import androidx.core.app.JobIntentService;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Listener for geofence transition changes.
 *
 * Receives geofence transition events from Location Services in the form of an Intent containing
 * the transition type and geofence id(s) that triggered the transition. Creates a notification
 * as the output.
 */
public class GeofenceTransitionsJobIntentService extends JobIntentService {
    private static LocalStorage localStorage;
    private static final int JOB_ID = 573;
    private static final String TAG = "GeofenceTransitionsIS";
    private static final String CHANNEL_ID = "channel_01";

    protected static final String GeofenceTransitionIntent = "com.cowbell.cordova.geofence.TRANSITION";
    protected GeoNotificationStore store;
    protected GeoNotificationNotifier notifier;
    protected BeepHelper beepHelper;

    public Context context;

    public GeofenceTransitionsJobIntentService() {
        super();
        Logger.setLogger(new Logger(GeofencePlugin.TAG, this, false));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        localStorage = new LocalStorage(this);
        store = new GeoNotificationStore(this);
    }

    /**
     * Convenience method for enqueuing work in to this service.
     */
    public static void enqueueWork(Context context, Intent intent) {
        enqueueWork(context, GeofenceTransitionsJobIntentService.class, JOB_ID, intent);
    }

    /**
     * Handles incoming intents.
     * @param intent sent by Location Services. This Intent is provided to Location
     *               Services (inside a PendingIntent) when addGeofences() is called.
     */
    @Override
    protected void onHandleWork(Intent intent) {
        beepHelper = new BeepHelper();
        store = new GeoNotificationStore(context);
        Logger.setLogger(new Logger(GeofencePlugin.TAG, context, false));
        Logger logger = Logger.getLogger();
        Intent broadcastIntent = new Intent(GeofenceTransitionIntent);

        // Required for implicit BroadcastReceiver to work for SDK 26+
        String packageName = context.getPackageName();
        broadcastIntent.setPackage(packageName);

        notifier = new GeoNotificationNotifier(
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE),
                context
        );

        try {
            // TODO: refactor this, too long
            // First check for errors
            GeofencingEvent geofencingEvent = GeofencingEvent.fromIntent(intent);
            if (geofencingEvent.hasError()) {
                // Get the error code with a static method
                int errorCode = geofencingEvent.getErrorCode();
                String error = "Location Services error: " + Integer.toString(errorCode);
                // Log the error
                logger.log(Log.ERROR, error);
                broadcastIntent.putExtra("error", error);
            }
            else {
                // Get the type of transition (entry or exit)
                int transitionType = geofencingEvent.getGeofenceTransition();

                List<Geofence> triggerList = geofencingEvent.getTriggeringGeofences();
                List<GeoNotification> geoNotifications = new ArrayList<>();
                for (Geofence fence : triggerList) {
                    String fenceId = fence.getRequestId();
                    GeoNotification geoNotification = store
                            .getGeoNotification(fenceId);

                    if (geoNotification != null && !GeofencePlugin.isSnoozed(geoNotification.id) && geoNotification.isWithinTimeRange()) {
                        geoNotification.transitionType = transitionType;
                        geoNotifications.add(geoNotification);
                    }
                }

                if (geofencingEvent.getTriggeringLocation() != null) {
                    broadcastIntent.putExtra("triggerLocation", geofencingEvent.getTriggeringLocation());
                }

                if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER
                        || transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    logger.log(Log.DEBUG, "Geofence transition detected");

                    if (geoNotifications.size() > 0) {
                        for (GeoNotification geoNotification : geoNotifications) {
                            if (geoNotification.notification != null && geoNotification.notification.canBeTriggered()) {
                                this.updateLastTriggeredByNotificationId(geoNotification.notification.id, geoNotifications);
                                notifier.notify(geoNotification.notification,
                                        transitionType == Geofence.GEOFENCE_TRANSITION_ENTER ? "enter" : "exit");
                                logger.log(Log.DEBUG, "Notification sent");
                            }
                            else {
                                logger.log(Log.DEBUG, "Frequency control. Skip notification");
                            }
                        }

                        broadcastIntent.putExtra("transitionData", Gson.get().toJson(geoNotifications));
                        GeofencePlugin.onTransitionReceived(geoNotifications);
                    }
                }
                else if (transitionType == Geofence.GEOFENCE_TRANSITION_DWELL) {
                    logger.log(Log.DEBUG, "Geofence transition dwell detected");

                    if (geoNotifications.size() > 0) {
                        broadcastIntent.putExtra("transitionData", Gson.get().toJson(geoNotifications));
                        GeofencePlugin.onTransitionReceived(geoNotifications);
                    }
                }
                else {
                    String error = "Geofence transition error: " + transitionType;
                    logger.log(Log.ERROR, error);
                    broadcastIntent.putExtra("error", error);
                }

                broadcastIntent.putExtra("trigger_listSize", triggerList.size());
                broadcastIntent.putExtra("trigger_transitionType", transitionType);

                for (GeoNotification geoNotification : geoNotifications) {
                    if (geoNotification.url != null) {
                        String transition = null;
                        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
                            transition = "ENTER";
                        if (transitionType == Geofence.GEOFENCE_TRANSITION_DWELL)
                            transition = "DWELL";
                        if (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)
                            transition = "EXIT";

                        PersistableBundle bundle = new PersistableBundle();
                        bundle.putString("id", geoNotification.id);
                        bundle.putString("url", geoNotification.url);
                        bundle.putString("authorization", geoNotification.authorization);
                        bundle.putString("transition", transition);

                        TimeZone tz = TimeZone.getTimeZone("UTC");
                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                        df.setTimeZone(tz);
                        bundle.putString("date", df.format(new Date()));

                        Log.i(GeofencePlugin.TAG, "Scheduling job for " + geoNotification.toJson());

                        JobScheduler jobScheduler =
                                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                        jobScheduler.schedule(
                                new JobInfo.Builder(1, new ComponentName(context, TransitionJobService.class))
                                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                                        .setExtras(bundle)
                                        .build()
                        );
                    }
                }
            }
        }
        catch (Exception e) {
            logger.log(e.getMessage(), e);

            String errMsg = e.getMessage();
            if (e.getStackTrace().length > 0) {
                StackTraceElement stackTrace_line1 = e.getStackTrace()[0];
                String stackStr = String.format("Class: %s, Line: %s", stackTrace_line1.getClassName(), stackTrace_line1.getLineNumber());
                errMsg = String.format("%s\nStack - %s", errMsg, stackStr);
            }

            broadcastIntent.putExtra("error", errMsg);
        }

        context.sendBroadcast(broadcastIntent);
    }

    public void onTransitionReceived(List<GeoNotification> notifications) throws JSONException {
        Log.d(TAG, "Transition Event Received!"+ notifications);
        for (int i=0; i<notifications.size();i++){
            String action = notifications.get(i).w_actions;
            GeoNotification notification = notifications.get(i);
            JSONArray obj = new JSONArray(action);
            // sendNotification(notification);
        }

    }

    /**
     * Gets transition details and returns them as a formatted string.
     *
     * @param geofenceTransition    The ID of the geofence transition.
     * @param triggeringGeofences   The geofence(s) triggered.
     * @return                      The transition details formatted as String.
     */
    private String getGeofenceTransitionDetails(
            int geofenceTransition,
            List<Geofence> triggeringGeofences) {

        String geofenceTransitionString = getTransitionString(geofenceTransition);

        // Get the Ids of each geofence that was triggered.
        ArrayList<String> triggeringGeofencesIdsList = new ArrayList<>();
        for (Geofence geofence : triggeringGeofences) {
            triggeringGeofencesIdsList.add(geofence.getRequestId());
        }
        String triggeringGeofencesIdsString = TextUtils.join(", ",  triggeringGeofencesIdsList);

        return geofenceTransitionString + ": " + triggeringGeofencesIdsString;
    }

    /**
     * Posts a notification in the notification bar when a transition is detected.
     * If the user clicks the notification, control goes to the MainActivity.
     * @param notificationDetails
     */
    private void sendNotification(GeoNotification notificationDetails) throws JSONException {
        // Get an instance of the Notification manager
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String description = "";
        if (notificationDetails.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER){
             description = "Triggered "+notificationDetails.name ;
        }
        if (notificationDetails.transitionType == Geofence.GEOFENCE_TRANSITION_EXIT){
             description = "Triggered "+notificationDetails.name ;
        }

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Geofencing";
            // Create the channel for the notification
            NotificationChannel mChannel =
                    new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);

            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(mChannel);
        }

        String packageName = getApplication().getPackageName();
        Intent launchIntent = getApplication().getPackageManager().getLaunchIntentForPackage(packageName);
        //String className = launchIntent.getComponent().getClassName();

        // Create an explicit content Intent that starts the main Activity.
        Intent notificationIntent = new Intent(getApplicationContext(), launchIntent.getComponent().getClass());

        // Construct a task stack.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);

        // Add the main Activity to the task stack as the parent.
        stackBuilder.addParentStack(getApplication().getPackageManager().getLaunchIntentForPackage(getApplication().getPackageName()).getComponent());

        // Push the content Intent onto the stack.
        stackBuilder.addNextIntent(notificationIntent);

        // Get a PendingIntent containing the entire back stack.
        PendingIntent notificationPendingIntent =
                stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        // Get a notification builder that's compatible with platform versions >= 4
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);

        // Define the notification settings.
        builder
                .setSmallIcon(_getResource("ic_launcher", "mipmap"))
                // In a real app, you may want to use a library like Volley
                // to decode the Bitmap.
                .setColor(Color.WHITE)
                .setContentTitle("Location Automation")
                .setContentText(description)
                .setContentIntent(notificationPendingIntent);

        // Set the Channel ID for Android O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID); // Channel ID
        }

        // Dismiss notification once the user touches it.
        builder.setAutoCancel(true);

        // Issue the notification
        mNotificationManager.notify((int)(Math.random()*(5000000-1000000+1)+1000000)  , builder.build());
    }

    /**
     * Maps geofence transition types to their human-readable equivalents.
     *
     * @param transitionType    A transition type constant defined in Geofence
     * @return                  A String indicating the type of transition
     */
    private String getTransitionString(int transitionType) {
        switch (transitionType) {
            case Geofence.GEOFENCE_TRANSITION_ENTER:
                return "Entered";
            case Geofence.GEOFENCE_TRANSITION_EXIT:
                return "Exited";
            default:
                return "Unknown Transition";
        }
    }

    private void updateLastTriggeredByNotificationId(int id, List<GeoNotification> geoList) {
        if (geoList != null) {
            for (GeoNotification geo : geoList) {
                if (geo.notification != null && geo.notification.id == id) {
                    geo.notification.setLastTriggered();
                    store.setGeoNotification(geo);
                }
            }
        }
    }

    private int _getResource(String name, String type) {
        String package_name = getApplication().getPackageName();
        Resources resources = getApplication().getResources();
        return resources.getIdentifier(name, type, package_name);
    }
}
