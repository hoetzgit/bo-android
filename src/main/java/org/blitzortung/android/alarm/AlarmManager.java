package org.blitzortung.android.alarm;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.location.Location;
import org.blitzortung.android.alarm.factory.AlarmObjectFactory;
import org.blitzortung.android.alarm.handler.AlarmStatusHandler;
import org.blitzortung.android.alarm.object.AlarmSector;
import org.blitzortung.android.alarm.object.AlarmStatus;
import org.blitzortung.android.app.controller.LocationHandler;
import org.blitzortung.android.app.view.PreferenceKey;
import org.blitzortung.android.data.beans.Stroke;
import org.blitzortung.android.util.MeasurementSystem;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class AlarmManager implements OnSharedPreferenceChangeListener, LocationHandler.Listener {

    private Collection<? extends Stroke> lastStrokes;

    public interface AlarmListener {
        void onAlarmResult(AlarmResult alarmResult);

        void onAlarmClear();
    }

    private final AlarmParameters alarmParameters;

    private Location location;

    private boolean alarmEnabled;

    private boolean alarmValid;

    // VisibleForTesting
    protected final Set<AlarmListener> alarmListeners;

    private final AlarmStatus alarmStatus;

    private final AlarmStatusHandler alarmStatusHandler;

    private final LocationHandler locationHandler;

    public AlarmManager(LocationHandler locationHandler, SharedPreferences preferences, AlarmObjectFactory alarmObjectFactory, AlarmParameters alarmParameters) {
        this.alarmParameters = alarmParameters;
        this.alarmStatus = alarmObjectFactory.createAlarmStatus(alarmParameters);
        this.alarmStatusHandler = alarmObjectFactory.createAlarmStatusHandler(alarmParameters);
        this.locationHandler = locationHandler;

        alarmListeners = new HashSet<AlarmListener>();

        preferences.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(preferences, PreferenceKey.ALARM_ENABLED);
        onSharedPreferenceChanged(preferences, PreferenceKey.MEASUREMENT_UNIT);

        alarmValid = false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String keyString) {
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.fromString(keyString));
    }

    private void onSharedPreferenceChanged(SharedPreferences sharedPreferences, PreferenceKey key) {
        switch (key) {
            case ALARM_ENABLED:
                alarmEnabled = sharedPreferences.getBoolean(key.toString(), false);

                if (alarmEnabled) {
                    locationHandler.requestUpdates(this);
                } else {
                    locationHandler.removeUpdates(this);
                    location = null;
                    broadcastClear();
                }
                break;

            case MEASUREMENT_UNIT:
                String measurementSystemName = sharedPreferences.getString(key.toString(), MeasurementSystem.METRIC.toString());

                alarmParameters.setMeasurementSystem(MeasurementSystem.valueOf(measurementSystemName));
                break;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        this.location = location;

        if (location == null) {
            invalidateAlarm();
        } else {
            if (lastStrokes != null) {
                checkStrokes(lastStrokes, true);
            }
        }
    }

    public boolean isAlarmEnabled() {
        return alarmEnabled;
    }

    public void checkStrokes(Collection<? extends Stroke> strokes, boolean areRealtimeStrokes) {
        alarmValid = isAlarmEnabled() && location != null && areRealtimeStrokes;
        lastStrokes = areRealtimeStrokes ? strokes : null;

        if (alarmValid) {
            alarmStatusHandler.checkStrokes(alarmStatus, strokes, location);
            broadcastResult(getAlarmResult());
        } else {
            invalidateAlarm();
        }
    }

    public AlarmResult getAlarmResult() {
        return alarmValid ? alarmStatusHandler.getCurrentActivity(alarmStatus) : null;
    }

    public String getTextMessage(float notificationDistanceLimit) {
        return alarmStatusHandler.getTextMessage(alarmStatus, notificationDistanceLimit);
    }

    public void addAlarmListener(AlarmListener alarmListener) {
        alarmListeners.add(alarmListener);
    }

    public Set<AlarmListener> getAlarmListeners() {
        return alarmListeners;
    }

    public void clearAlarmListeners() {
        alarmListeners.clear();
    }

    public void removeAlarmListener(AlarmListener alarmView) {
        alarmListeners.remove(alarmView);
    }

    public Collection<AlarmSector> getAlarmSectors() {
        return alarmStatus.getSectors();
    }

    public AlarmParameters getAlarmParameters() {
        return alarmParameters;
    }

    public AlarmStatus getAlarmStatus() {
        return alarmValid ? alarmStatus : null;
    }

    private void invalidateAlarm() {
        boolean previousAlarmValidState = alarmValid;
        alarmValid = false;

        if (previousAlarmValidState == true) {
            alarmStatus.clearResults();
            broadcastClear();
        }
    }

    private void broadcastClear() {
        for (AlarmListener alarmListener : alarmListeners) {
            alarmListener.onAlarmClear();
        }
    }

    private void broadcastResult(AlarmResult alarmResult) {
        for (AlarmListener alarmListener : alarmListeners) {
            alarmListener.onAlarmResult(alarmResult);
        }
    }

}
