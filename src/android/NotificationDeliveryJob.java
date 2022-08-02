package com.cowbell.cordova.geofence;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;


import com.google.android.gms.location.Geofence;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("SpecifyJobSchedulerIdRange")
public class NotificationDeliveryJob extends JobService {

    protected GeoNotificationStore store;
    protected GeoNotificationNotifier notifier;
    public Context context;

    @Override
    public boolean onStartJob(final JobParameters jobParameters) {
        this.store = new GeoNotificationStore(this);
        PersistableBundle params = jobParameters.getExtras();
        final String id = params.getString("id");
        final String transition = params.getString("transition") == "ENTER"? "enter": "exit";
        final String date = params.getString("date");
        context = this;
        notifier = new GeoNotificationNotifier(
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE),
                context
        );
        Thread thread = new Thread(() -> {
            try {
                // Get the notification:
                GeoNotification geoNotification = this.store.getGeoNotification(id);
                // Display:
                if (this.shouldDisplay(geoNotification)) {
                    notifier.notify(geoNotification.notification, transition);
                    ArrayList<GeoNotification> geoNotifications = new ArrayList<GeoNotification>();
                    geoNotifications.add(geoNotification);
                    GeofenceJsEvent.onTransitionReceived(geoNotifications);
                    this.clearIsLast();
                }
                jobFinished(jobParameters, false);
            } catch (Exception exception) {
                // It is possible to have no network during transition from Cellular to Wifi
                Log.e(GeofencePlugin.TAG, "Error while sending geofence transition, rescheduling", exception);
                jobFinished(jobParameters, true);
            }
        });
        thread.start();

        return true; // Async
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }

    /**
     * Determines if we should display the notification.
     *
     * @param notification The notification.
     * @return bool
     */
    private boolean shouldDisplay(GeoNotification notification) {
        return notification.isLast && notification.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER;
    }

    /**
     * Sets all notifications isLast to false
     *
     */
    private void clearIsLast() {
        List<GeoNotification> geoNotifications = this.store.getAll();
        for (GeoNotification notification : geoNotifications) {
            notification.isLast = false;
            store.setGeoNotification(notification);
        }
    }

}
