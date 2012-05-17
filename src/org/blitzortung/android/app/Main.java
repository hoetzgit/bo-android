package org.blitzortung.android.app;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.blitzortung.android.data.DataListener;
import org.blitzortung.android.data.Provider;
import org.blitzortung.android.data.provider.DataResult;
import org.blitzortung.android.map.OwnMapActivity;
import org.blitzortung.android.map.OwnMapView;
import org.blitzortung.android.map.overlay.StationsOverlay;
import org.blitzortung.android.map.overlay.StrokesOverlay;
import org.blitzortung.android.map.overlay.color.StrokeColorHandler;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.maps.MyLocationOverlay;
import com.google.android.maps.Overlay;

public class Main extends OwnMapActivity implements LocationListener, DataListener, OnSharedPreferenceChangeListener,
		AlarmManager.AlarmListener {

	private static final String TAG = "Main";

	TextView status;

	TextView warning;

	StrokesOverlay strokesOverlay;

	StationsOverlay stationsOverlay;

	private TimerTask timerTask;

	private AlarmManager alarmManager;

	private Provider provider;

	// Button rasterPointsSwitch;

	MyLocationOverlay myLocationOverlay;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(isDebugBuild() ? R.layout.main_debug : R.layout.main);

		setMapView((OwnMapView) findViewById(R.id.mapview));

		getMapView().setBuiltInZoomControls(true);

		myLocationOverlay = new MyLocationOverlay(getBaseContext(), getMapView());

		status = (TextView) findViewById(R.id.status);

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		preferences.registerOnSharedPreferenceChangeListener(this);

		strokesOverlay = new StrokesOverlay(this, new StrokeColorHandler(preferences));

		// stationsOverlay = new StationsOverlay(this, new
		// StationColorHandler(preferences));

		getMapView().addZoomListener(new OwnMapView.ZoomListener() {

			@Override
			public void onZoom(int zoomLevel) {
				strokesOverlay.updateZoomLevel(zoomLevel);
				// stationsOverlay.updateShapeSize(zoomLevel);
			}

		});

		List<Overlay> mapOverlays = getMapView().getOverlays();

		mapOverlays.add(strokesOverlay);
		// mapOverlays.add(stationsOverlay);

		provider = new Provider(preferences, (ProgressBar) findViewById(R.id.progress), (ImageView) findViewById(R.id.error_indicator),
				this);

		timerTask = new TimerTask(this, preferences, provider);

		warning = (TextView) findViewById(R.id.warning);
		alarmManager = new AlarmManager(this, preferences, timerTask);
		alarmManager.setAlarmListener(this);

		onSharedPreferenceChanged(preferences, Preferences.MAP_TYPE_KEY);
		onSharedPreferenceChanged(preferences, Preferences.SHOW_LOCATION_KEY);

		getMapView().invalidate();
	}

	public void setStatusText(String statusText) {
		status.setText(statusText);
	}

	public boolean isDebugBuild() {
		boolean dbg = false;
		try {
			PackageManager pm = getPackageManager();
			PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);

			dbg = ((pi.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
		} catch (Exception e) {
		}
		return dbg;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate menu from XML resource
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle all of the possible menu actions.
		switch (item.getItemId()) {
		case R.id.menu_info:
			showDialog(R.id.info_dialog);
			break;
			
		case R.id.menu_legend:
			showDialog(R.id.legend_dialog);
			break;

		case R.id.menu_preferences:
			startActivity(new Intent(this, Preferences.class));
			break;
		}
		return super.onOptionsItemSelected(item);

	}

	@Override
	public void onResume() {
		super.onResume();
		Log.v(TAG, "onResume()");
		timerTask.onResume();

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (preferences.getBoolean(Preferences.SHOW_LOCATION_KEY, false)) {
			myLocationOverlay.enableMyLocation();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		Log.v(TAG, "onPause()");
		timerTask.onPause();

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (preferences.getBoolean(Preferences.SHOW_LOCATION_KEY, false)) {
			myLocationOverlay.disableMyLocation();
		}
	}

	@Override
	protected boolean isRouteDisplayed() {
		return false;
	}

	@Override
	public void onLocationChanged(Location location) {
		Log.v(TAG, "New location received");
	}

	@Override
	public void onProviderDisabled(String arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onProviderEnabled(String arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onDataUpdate(DataResult result) {
		if (result.containsStrokes()) {
			Calendar expireTime = new GregorianCalendar();

			expireTime.add(Calendar.MINUTE, -provider.getMinutes());

			strokesOverlay.setRaster(result.getRaster());
			strokesOverlay.addAndExpireStrokes(result.getStrokes(), expireTime.getTime());

			timerTask.setNumberOfStrokes(strokesOverlay.getTotalNumberOfStrokes());

			if (alarmManager.isAlarmEnabled()) {
				alarmManager.check(result);
			}
			strokesOverlay.refresh();
		}

		if (stationsOverlay != null && result.containsStations()) {
			stationsOverlay.setStations(result.getStations());
			stationsOverlay.refresh();
		}

		getMapView().invalidate();
	}

	@Override
	public void onDataReset() {
		strokesOverlay.clear();
		timerTask.restart();
		strokesOverlay.refresh();
		if (stationsOverlay != null) {
			stationsOverlay.clear();
			stationsOverlay.refresh();
		}
	}

	protected Dialog onCreateDialog(int id) {
		Dialog dialog;
		switch (id) {
		case R.id.info_dialog:
			dialog = new InfoDialog(this);
			break;
			
		case R.id.legend_dialog:
			dialog = new LegendDialog(this, strokesOverlay);
			break;
			
		default:
			dialog = null;
		}
		return dialog;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(Preferences.MAP_TYPE_KEY)) {
			String mapTypeString = sharedPreferences.getString(Preferences.MAP_TYPE_KEY, "SATELLITE");
			getMapView().setSatellite(mapTypeString.equals("SATELLITE"));
			strokesOverlay.refresh();
			if (stationsOverlay != null) {
				stationsOverlay.refresh();
			}
		} else if (key.equals(Preferences.RASTER_SIZE_KEY)) {
			timerTask.restart();
		} else if (key.equals(Preferences.SHOW_LOCATION_KEY)) {
			boolean showLocation = sharedPreferences.getBoolean(Preferences.SHOW_LOCATION_KEY, false);
			List<Overlay> mapOverlays = getMapView().getOverlays();

			if (showLocation) {
				myLocationOverlay.enableMyLocation();
				mapOverlays.add(myLocationOverlay);
			} else {
				mapOverlays.remove(myLocationOverlay);
				myLocationOverlay.disableMyLocation();
			}
		}
	}

	static String[] directionStrings = { "S", "SW", "W", "NW", "N", "NO", "O", "SO" };

	private String getDirectionString(double bearing) {
		int directionCount = directionStrings.length;
		double bearingDivider = 360 / directionCount;

		int direction = ((int) (Math.round(bearing / bearingDivider)) + directionCount / 2) % directionCount;
		return directionStrings[direction];
	}

	@Override
	public void onAlarmResult(double distance, double bearing) {
		int textColorResource;
		String warningText;
		if (distance >= 0.0) {
			if (distance > 100.0) {
				textColorResource = R.color.Green;
			} else if (distance > 50.0) {
				textColorResource = R.color.Yellow;
			} else {
				textColorResource = R.color.Red;
				Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
				vibrator.vibrate(40);
			}
			warningText = String.format("%.0fkm %s", distance / 1000, getDirectionString(bearing));
		} else {
			textColorResource = R.color.Green;
			warningText = "-";
		}
		warning.setTextColor(getResources().getColor(textColorResource));

		warning.setText(warningText);
	}

	@Override
	public void onAlarmClear() {
		warning.setText("");
	}

}