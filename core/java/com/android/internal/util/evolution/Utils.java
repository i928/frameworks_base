/*
 * Copyright (C) 2017-2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.evolution;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;

import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.widget.Toast;
import com.android.internal.R;

import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;

import android.util.Log;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.CollectionUtils;

import java.util.List;

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

public class Utils {

    private static final String TAG = "Utils";

    public static void restartApp(String appName, Context context) {
        new RestartAppTask(appName, context).execute();
    }

    private static class RestartAppTask extends AsyncTask<Void, Void, Void> {
        private Context mContext;
        private String mApp;

        public RestartAppTask(String appName, Context context) {
            super();
            mContext = context;
            mApp = appName;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                IActivityManager ams = ActivityManager.getService();
                for (ActivityManager.RunningAppProcessInfo app: am.getRunningAppProcesses()) {
                    if (mApp.equals(app.processName)) {
                        ams.killApplicationProcess(app.processName, app.uid);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    public static boolean isPackageInstalled(Context context, String packageName, boolean ignoreState) {
        if (packageName != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPackageInstalled(Context context, String packageName) {
        return isPackageInstalled(context, packageName, true);
    }

    public static boolean isPackageEnabled(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        }
    }

    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public static boolean hasNavbarByDefault(Context context) {
        boolean needsNav = context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            needsNav = false;
        } else if ("0".equals(navBarOverride)) {
            needsNav = true;
        }
        return needsNav;
    }

    public static void restartSystemUI() {
        final IStatusBarService mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        try {
            mBarService.restartSystemUI();
        } catch (RemoteException e) {
        }
    }

    public static String getDefaultLauncher(Context context) {
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final String packageName = CollectionUtils.firstOrNull(
                roleManager.getRoleHolders(RoleManager.ROLE_HOME));
        return packageName != null ? packageName : "";
    }

    public static void forceStopDefaultLauncher(Context context) {
        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        try {
            activityManager.forceStopPackageAsUser(getDefaultLauncher(context), UserHandle.USER_CURRENT);
        } catch (Exception ignored) {}
    }

    public static void toggleOverlay(Context context, String overlayName, boolean enable) {
        OverlayManager overlayManager = context.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            Log.e(TAG, "OverlayManager is not available");
            return;
        }

        OverlayIdentifier overlayId = getOverlayID(overlayManager, overlayName);
        if (overlayId == null) {
            Log.e(TAG, "Overlay ID not found for " + overlayName);
            return;
        }

        OverlayManagerTransaction.Builder transaction = new OverlayManagerTransaction.Builder();
        transaction.setEnabled(overlayId, enable, UserHandle.USER_CURRENT);

        try {
            overlayManager.commit(transaction.build());
        } catch (Exception e) {
            Log.e(TAG, "Error toggling overlay", e);
        }
    }

    private static OverlayIdentifier getOverlayID(OverlayManager overlayManager, String name) {
        try {
            if (name.contains(":")) {
                String[] parts = name.split(":");
                List<OverlayInfo> infos = overlayManager.getOverlayInfosForTarget(parts[0], UserHandle.CURRENT);
                for (OverlayInfo info : infos) {
                    if (parts[1].equals(info.getOverlayName())) return info.getOverlayIdentifier();
                }
            } else {
                OverlayInfo info = overlayManager.getOverlayInfo(name, UserHandle.CURRENT);
                if (info != null) return info.getOverlayIdentifier();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving overlay ID", e);
        }
        return null;
    }

    public static class SleepModeController {
        private final Resources mResources;
        private final Context mUiContext;

        private Context mContext;
        private AudioManager mAudioManager;
        private NotificationManager mNotificationManager;
        private WifiManager mWifiManager;
        private SensorPrivacyManager mSensorPrivacyManager;
        private BluetoothAdapter mBluetoothAdapter;
        private int mSubscriptionId;
        private Toast mToast;

        private boolean mSleepModeEnabled;

        private static boolean mWifiState;
        private static boolean mCellularState;
        private static boolean mBluetoothState;
        private static int mRingerState;
        private static int mZenState;

        private static final String TAG = "SleepModeController";
        // What Sleep Mode turned off, kept across reboots: "wifi,cellular,bluetooth,ringer,zen".
        // The fields above are in-memory only, so a reboot inside the sleep window used to
        // record the already-disabled radios and leave them off when Sleep Mode ended.
        private static final String SLEEP_MODE_SAVED_STATE = "sleep_mode_saved_state";
        private static final int SLEEP_NOTIFICATION_ID = 727;
        public static final String SLEEP_MODE_TURN_OFF = "android.intent.action.SLEEP_MODE_TURN_OFF";

        // Wi-Fi during Sleep Mode follows use instead of staying off all night: off while the
        // screen is off on battery, back on as soon as the phone is used or charging, and off
        // again WIFI_OFF_DELAY_MS after the screen goes off (quick glances don't bounce it).
        // Only applies when Wi-Fi was on when Sleep Mode started; a manual Wi-Fi change during
        // sleep hands control back to the user until Sleep Mode ends.
        private static final long WIFI_OFF_DELAY_MS = 5 * 60 * 1000;
        private static final String WIFI_OFF_ALARM_TAG = "SleepModeWifiOff";
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private boolean mWifiManaged;
        private boolean mWifiUserOverride;
        private boolean mWifiOffPending;
        // State we last asked for and haven't seen confirmed yet; null when idle.
        private Boolean mWifiRequested;

        private final AlarmManager.OnAlarmListener mWifiOffAlarm = () -> {
            mWifiOffPending = false;
            if (mWifiManaged && !mWifiUserOverride && !isInUse()) {
                setManagedWifi(false);
            }
        };

        private final BroadcastReceiver mWifiPolicyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                    onWifiStateChanged(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN));
                } else {
                    updateManagedWifi();
                }
            }
        };

        private boolean isInUse() {
            final PowerManager pm = mContext.getSystemService(PowerManager.class);
            if (pm != null && pm.isInteractive()) return true;
            final Intent battery = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }

        private void setManagedWifi(boolean enable) {
            if (isWifiEnabled() == enable) return;
            mWifiRequested = enable;
            setWifiEnabled(enable);
        }

        private void onWifiStateChanged(int state) {
            if (!mWifiManaged) return;
            final boolean on;
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                on = true;
            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                on = false;
            } else {
                return;
            }
            if (mWifiRequested != null && mWifiRequested == on) {
                mWifiRequested = null;
                return;
            }
            // Not our request: the user (or airplane mode etc.) changed Wi-Fi.
            Log.i(TAG, "Wi-Fi changed by the user during Sleep Mode, leaving it alone");
            mWifiUserOverride = true;
            cancelWifiOff();
        }

        private void updateManagedWifi() {
            if (!mWifiManaged || mWifiUserOverride) return;
            if (isInUse()) {
                cancelWifiOff();
                setManagedWifi(true);
            } else if (isWifiEnabled() && !mWifiOffPending) {
                final AlarmManager am = mContext.getSystemService(AlarmManager.class);
                if (am == null) {
                    setManagedWifi(false);
                    return;
                }
                mWifiOffPending = true;
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + WIFI_OFF_DELAY_MS,
                        WIFI_OFF_ALARM_TAG, mHandler::post, null, mWifiOffAlarm);
            }
        }

        private void cancelWifiOff() {
            if (!mWifiOffPending) return;
            mWifiOffPending = false;
            final AlarmManager am = mContext.getSystemService(AlarmManager.class);
            if (am != null) am.cancel(mWifiOffAlarm);
        }

        private void startManagingWifi() {
            mWifiManaged = true;
            mWifiUserOverride = false;
            mWifiRequested = null;
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiPolicyReceiver, filter, null, mHandler,
                    Context.RECEIVER_EXPORTED);
            // Screen off on battery when Sleep Mode starts: off right away, as before.
            if (!isInUse()) {
                setManagedWifi(false);
            }
        }

        private void stopManagingWifi() {
            if (!mWifiManaged) return;
            mWifiManaged = false;
            cancelWifiOff();
            try {
                mContext.unregisterReceiver(mWifiPolicyReceiver);
            } catch (IllegalArgumentException e) {
                // not registered
            }
        }

        public SleepModeController(Context context) {
            mContext = context;
            mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            mResources = mContext.getResources();

            mSleepModeEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

            SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
            observer.observe();
            observer.update();
        }

        private TelephonyManager getTelephonyManager() {
            int subscriptionId = mSubscriptionId;

            // If mSubscriptionId is invalid, get default data sub.
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId();
            }

            // If data sub is also invalid, get any active sub.
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                int[] activeSubIds = SubscriptionManager.from(mContext).getActiveSubscriptionIdList();
                if (!ArrayUtils.isEmpty(activeSubIds)) {
                    subscriptionId = activeSubIds[0];
                }
            }

            return mContext.getSystemService(
                    TelephonyManager.class).createForSubscriptionId(subscriptionId);
        }

        private boolean isWifiEnabled() {
            if (mWifiManager == null) {
                mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            }
            try {
                return mWifiManager.isWifiEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setWifiEnabled(boolean enable) {
            if (mWifiManager == null) {
                mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            }
            try {
                mWifiManager.setWifiEnabled(enable);
            } catch (Exception e) {
            }
        }

        private boolean isBluetoothEnabled() {
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }
            try {
                return mBluetoothAdapter.isEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setBluetoothEnabled(boolean enable) {
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }
            try {
                if (enable) mBluetoothAdapter.enable();
                else mBluetoothAdapter.disable();
            } catch (Exception e) {
            }
        }

        private boolean isSensorEnabled() {
            if (mSensorPrivacyManager == null) {
                mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            }
            try {
                return !mSensorPrivacyManager.isAllSensorPrivacyEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setSensorEnabled(boolean enable) {
            if (mSensorPrivacyManager == null) {
                mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            }
            try {
                mSensorPrivacyManager.setAllSensorPrivacy(!enable);
            } catch (Exception e) {
            }
        }

        private int getZenMode() {
            if (mNotificationManager == null) {
                mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            }
            try {
                return mNotificationManager.getZenMode();
            } catch (Exception e) {
                return -1;
            }
        }

        private void setZenMode(int mode) {
            if (mNotificationManager == null) {
                mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            }
            try {
                mNotificationManager.setZenMode(mode, null, TAG);
            } catch (Exception e) {
            }
        }

        private int getRingerModeInternal() {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
            try {
                return mAudioManager.getRingerModeInternal();
            } catch (Exception e) {
                return -1;
            }
        }

        private void setRingerModeInternal(int mode) {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
            try {
                mAudioManager.setRingerModeInternal(mode);
            } catch (Exception e) {
            }
        }

        private void saveState() {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), SLEEP_MODE_SAVED_STATE,
                    mWifiState + "," + mCellularState + "," + mBluetoothState + ","
                    + mRingerState + "," + mZenState, UserHandle.USER_CURRENT);
        }

        /** @return true if a state saved by an earlier {@link #enable()} was loaded. */
        private boolean loadState() {
            final String saved = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    SLEEP_MODE_SAVED_STATE, UserHandle.USER_CURRENT);
            if (saved == null || saved.isEmpty()) return false;
            try {
                final String[] v = saved.split(",");
                mWifiState = Boolean.parseBoolean(v[0]);
                mCellularState = Boolean.parseBoolean(v[1]);
                mBluetoothState = Boolean.parseBoolean(v[2]);
                mRingerState = Integer.parseInt(v[3]);
                mZenState = Integer.parseInt(v[4]);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Ignoring bad " + SLEEP_MODE_SAVED_STATE + ": " + saved);
                return false;
            }
        }

        private void clearState() {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), SLEEP_MODE_SAVED_STATE,
                    null, UserHandle.USER_CURRENT);
        }

        private void enable() {
            if (!ActivityManager.isSystemReady()) return;

            // Re-enabled after a reboot inside the sleep window: keep what was saved before the
            // reboot instead of recording the radios this mode already turned off.
            final boolean restored = loadState();

            // Disable Wi-Fi
            final boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (disableWifi) {
                if (!restored) mWifiState = isWifiEnabled();
                if (mWifiState) {
                    startManagingWifi();
                }
            }

            // Disable Bluetooth
            final boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableBluetooth) {
                if (!restored) mBluetoothState = isBluetoothEnabled();
                setBluetoothEnabled(false);
            }

            // Disable Mobile Data
            final boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableData) {
                if (!restored) mCellularState = getTelephonyManager().isDataEnabled();
                getTelephonyManager().setDataEnabled(false);
            }

            // Disable Sensors
            final boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableSensors) {
                setSensorEnabled(false);
            }

            // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
            final int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
            if (ringerMode != 0) {
                if (!restored) {
                    mRingerState = getRingerModeInternal();
                    mZenState = getZenMode();
                }
                if (ringerMode == 1) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                    setZenMode(ZEN_MODE_OFF);
                } else if (ringerMode == 2) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                    setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
                } else if (ringerMode == 3) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                    setZenMode(ZEN_MODE_OFF);
                }
            }

            if (!restored) saveState();

            showToast(mResources.getString(R.string.sleep_mode_enabled_toast), Toast.LENGTH_LONG);
            addNotification();
        }

        private void disable() {
            if (!ActivityManager.isSystemReady()) return;

            // After a reboot the in-memory fields are defaults; use the saved state.
            loadState();

            // Enable Wi-Fi
            final boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            final boolean userChangedWifi = mWifiUserOverride;
            stopManagingWifi();
            if (disableWifi && !userChangedWifi && mWifiState != isWifiEnabled()) {
                setWifiEnabled(mWifiState);
            }

            // Enable Bluetooth
            final boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableBluetooth && mBluetoothState != isBluetoothEnabled()) {
                setBluetoothEnabled(mBluetoothState);
            }

            // Enable Mobile Data
            final boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableData && mCellularState != getTelephonyManager().isDataEnabled()) {
                getTelephonyManager().setDataEnabled(mCellularState);
            }

            // Enable Sensors
            final boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableSensors) {
                setSensorEnabled(true);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                if (!isSensorEnabled()) {
                    setSensorEnabled(true);
                }
            }

            // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
            final int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
            if (ringerMode != 0 && (mRingerState != getRingerModeInternal() ||
                    mZenState != getZenMode())) {
                setRingerModeInternal(mRingerState);
                setZenMode(mZenState);
            }

            clearState();

            showToast(mResources.getString(R.string.sleep_mode_disabled_toast), Toast.LENGTH_LONG);
            mNotificationManager.cancel(SLEEP_NOTIFICATION_ID);
        }

        private void addNotification() {
            Intent intent = new Intent(SLEEP_MODE_TURN_OFF);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Display a notification
            Notification.Builder builder = new Notification.Builder(mContext, SystemNotificationChannels.SLEEP)
                .setTicker(mResources.getString(R.string.sleep_mode_notification_title))
                .setContentTitle(mResources.getString(R.string.sleep_mode_notification_title))
                .setContentText(mResources.getString(R.string.sleep_mode_notification_content))
                .setSmallIcon(R.drawable.ic_sleep)
                .setWhen(java.lang.System.currentTimeMillis())
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);

            Notification notification = builder.build();
            mNotificationManager.notify(SLEEP_NOTIFICATION_ID, notification);
        }

        private void showToast(String msg, int duration) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mToast != null) mToast.cancel();
                        mToast = Toast.makeText(mUiContext, msg, duration);
                        mToast.show();
                    } catch (Exception e) {
                    }
                }
            });
        }

        private void setSleepMode(boolean enabled) {
            if (mSleepModeEnabled == enabled) {
                return;
            }

            mSleepModeEnabled = enabled;

            if (mSleepModeEnabled) {
                enable();
            } else {
                disable();
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
            }

            void observe() {
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.SLEEP_MODE_ENABLED), false, this,
                        UserHandle.USER_ALL);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                update();
            }

            void update() {
                final boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
                setSleepMode(enabled);
            }
        }
    }
}
