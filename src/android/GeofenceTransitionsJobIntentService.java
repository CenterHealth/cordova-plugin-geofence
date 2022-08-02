package com.cowbell.cordova.geofence;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import android.os.PersistableBundle;
import android.util.Log;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final int JOB_ID = 573;

    protected static final String GeofenceTransitionIntent = "com.cowbell.cordova.geofence.TRANSITION";
    protected GeoNotificationStore store;

    public Context context;

    public GeofenceTransitionsJobIntentService() {
        super();
        Logger.setLogger(new Logger(GeofencePlugin.TAG, this, false));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
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
    protected void onHandleWork(@NonNull Intent intent) {
        store = new GeoNotificationStore(context);
        Intent broadcastIntent = new Intent(GeofenceTransitionIntent);
        Logger logger = getLogger();

        // Required for implicit BroadcastReceiver to work for SDK 26+
        String packageName = context.getPackageName();
        broadcastIntent.setPackage(packageName);

        try {
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
                processNotifications(geofencingEvent, broadcastIntent);
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

    /**
     * For building the notifications.
     *
     * @param geofencingEvent The geofencing event.
     * @param broadcastIntent The intent.
     */
    private void processNotifications(GeofencingEvent geofencingEvent, Intent broadcastIntent) {
        Logger logger = getLogger();

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

        if ((transitionType == Geofence.GEOFENCE_TRANSITION_ENTER
                || transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) && geoNotifications.size() > 0) {
            this.onEnterExit(geoNotifications, broadcastIntent, transitionType, logger);
        }

        else if (transitionType == Geofence.GEOFENCE_TRANSITION_DWELL) {
            this.onDwell(geoNotifications, broadcastIntent, logger);
        }
        else {
            String error = "Geofence transition error: " + transitionType;
            logger.log(Log.ERROR, error);
            broadcastIntent.putExtra("error", error);
        }

        broadcastIntent.putExtra("trigger_listSize", triggerList.size());
        broadcastIntent.putExtra("trigger_transitionType", transitionType);
        this.scheduleSendingToServer(geoNotifications, transitionType);
    }

    /**
     * Handles on enter/exit geofence event.
     *
     * @param geoNotifications The notifications.
     * @param broadcastIntent The intent.
     * @param transitionType The transition type.
     * @param logger The logger.
     */
    private void onEnterExit(List<GeoNotification> geoNotifications, Intent broadcastIntent, int transitionType, Logger logger) {
        Logger.setLogger(new Logger(GeofencePlugin.TAG, context, false));
        // Old implementation:
        logger.log(Log.DEBUG, "Geofence transition detected");
        for (GeoNotification geoNotification : geoNotifications) {
            if (geoNotification.notification != null && geoNotification.notification.canBeTriggered()) {
                // Schedule displaying:
                this.setIsLast(geoNotification);
                this.scheduleDisplaying(geoNotification, transitionType);
                this.updateLastTriggeredByNotificationId(geoNotification.notification.id, geoNotifications);
            }
            else {
                logger.log(Log.DEBUG, "Frequency control. Skip notification");
            }
        }
    }

    /**
     * Handles on dwell geofence event.
     *
     * @param geoNotifications The notifications.
     * @param broadcastIntent The intent.
     * @param logger The logger.
     */
    private void onDwell(List<GeoNotification> geoNotifications, Intent broadcastIntent, Logger logger) {
        logger.log(Log.DEBUG, "Geofence transition dwell detected");

        if (geoNotifications.size() > 0) {
            broadcastIntent.putExtra("transitionData", Gson.get().toJson(geoNotifications));
            GeofenceJsEvent.onTransitionReceived(geoNotifications);
        }
    }

    private Logger getLogger() {
        Logger.setLogger(new Logger(GeofencePlugin.TAG, context, false));
        return Logger.getLogger();
    }

    /**
     * Private method for scheduling notifying the server about the transitions.
     *
     * @param geoNotifications the notifications.
     * @param transitionType the transition type
     */
    private void scheduleSendingToServer(List<GeoNotification> geoNotifications, int transitionType) {
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

                int jobId = randomInt(1000, 20000);
                JobScheduler jobScheduler =
                        (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                jobScheduler.schedule(
                        new JobInfo.Builder(jobId, new ComponentName(context, TransitionJobService.class))
                                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                                .setExtras(bundle)
                                .build()
                );
            }
        }
    }


    /**
     * Adds the notification to the displaying queue.
     *
     * @param geoNotification The notification.
     * @param transitionType The transition type
     */
    private void scheduleDisplaying(GeoNotification geoNotification, int transitionType) {
        GeofenceConfig config = store.getConfig();
        String transition = null;
        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
            transition = "ENTER";
        if (transitionType == Geofence.GEOFENCE_TRANSITION_DWELL)
            transition = "DWELL";
        if (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)
            transition = "EXIT";

        PersistableBundle bundle = new PersistableBundle();
        bundle.putString("id", geoNotification.id);
        bundle.putString("transition", transition);

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        TimeZone tz = TimeZone.getTimeZone("UTC");
        df.setTimeZone(tz);
        bundle.putString("date", df.format(new Date()));

        Log.i(GeofencePlugin.TAG, "Scheduling notification displaying job" + geoNotification.toJson());

        JobScheduler jobScheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        int jobId = randomInt(1000, 20000);
        jobScheduler.schedule(
                new JobInfo.Builder(jobId, new ComponentName(context, NotificationDeliveryJob.class))
                        .setExtras(bundle)
                        .setMinimumLatency(1000 * config.delay)
                        .build()
        );
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

    private void setIsLast(GeoNotification geoNotification) {
        List<GeoNotification> geoNotifications = this.store.getAll();
        for (GeoNotification notification : geoNotifications) {
            notification.isLast = false;
            store.setGeoNotification(notification);
        }
        geoNotification.isLast = true;
        this.store.setGeoNotification(geoNotification);
    }

    private int randomInt(int min, int max){
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
