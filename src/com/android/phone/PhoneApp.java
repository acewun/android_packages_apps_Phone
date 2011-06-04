/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

package com.android.phone;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.ProgressDialog;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.LocalPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.phone.OtaUtils.CdmaOtaScreenState;
import com.android.server.sip.SipService;

import android.os.Vibrator;
import android.app.AlarmManager;
import android.app.PendingIntent;

/**
 * Top-level Application class for the Phone app.
 */
public class PhoneApp extends Application implements AccelerometerListener.OrientationListener {
    /* package */ static final String LOG_TAG = "PhoneApp";

    /**
     * Phone app-wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (PhoneApp.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     *
     * ***** DO NOT SUBMIT WITH DBG_LEVEL > 0 *************
     */
    /* package */ static final int DBG_LEVEL = 0;

    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    // Message codes; see mHandler below.
    private static final int EVENT_SIM_NETWORK_LOCKED = 3;
    private static final int EVENT_WIRED_HEADSET_PLUG = 7;
    private static final int EVENT_SIM_STATE_CHANGED = 8;
    private static final int EVENT_UPDATE_INCALL_NOTIFICATION = 9;
    private static final int EVENT_DATA_ROAMING_DISCONNECTED = 10;
    private static final int EVENT_DATA_ROAMING_OK = 11;
    private static final int EVENT_UNSOL_CDMA_INFO_RECORD = 12;
    private static final int EVENT_DOCK_STATE_CHANGED = 13;
    private static final int EVENT_TTY_PREFERRED_MODE_CHANGED = 14;
    private static final int EVENT_TTY_MODE_GET = 15;
    private static final int EVENT_TTY_MODE_SET = 16;
    private static final int EVENT_TECHNOLOGY_CHANGED = 17;
    private static final int EVENT_START_SIP_SERVICE = 18;
    // The MMI codes are also used by the InCallScreen.
    public static final int MMI_INITIATE = 51;
    public static final int MMI_COMPLETE = 52;
    public static final int MMI_CANCEL = 53;
    // Don't use message codes larger than 99 here; those are reserved for
    // the individual Activities of the Phone UI.

    /**
     * Allowable values for the poke lock code (timeout between a user activity and the
     * going to sleep), please refer to {@link com.android.server.PowerManagerService}
     * for additional reference.
     *   SHORT uses the short delay for the timeout (SHORT_KEYLIGHT_DELAY, 6 sec)
     *   MEDIUM uses the medium delay for the timeout (MEDIUM_KEYLIGHT_DELAY, 15 sec)
     *   DEFAULT is the system-wide default delay for the timeout (1 min)
     */
    public enum ScreenTimeoutDuration {
        SHORT,
        MEDIUM,
        DEFAULT
    }

    /**
     * Allowable values for the wake lock code.
     *   SLEEP means the device can be put to sleep.
     *   PARTIAL means wake the processor, but we display can be kept off.
     *   FULL means wake both the processor and the display.
     */
    public enum WakeState {
        SLEEP,
        PARTIAL,
        FULL
    }

    private static PhoneApp sMe;

    // A few important fields we expose to the rest of the package
    // directly (rather than thru set/get methods) for efficiency.
    Phone phone;
    CallNotifier notifier;
    Ringer ringer;
    BluetoothHandsfree mBtHandsfree;
    PhoneInterfaceManager phoneMgr;
    CallManager mCM;
    int mBluetoothHeadsetState = BluetoothHeadset.STATE_ERROR;
    int mBluetoothHeadsetAudioState = BluetoothHeadset.STATE_ERROR;
    boolean mShowBluetoothIndication = false;
    static int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // Internal PhoneApp Call state tracker
    CdmaPhoneCallState cdmaPhoneCallState;

    // The InCallScreen instance (or null if the InCallScreen hasn't been
    // created yet.)
    private InCallScreen mInCallScreen;

    // The currently-active PUK entry activity and progress dialog.
    // Normally, these are the Emergency Dialer and the subsequent
    // progress dialog.  null if there is are no such objects in
    // the foreground.
    private Activity mPUKEntryActivity;
    private ProgressDialog mPUKEntryProgressDialog;

    private boolean mIsSimPinEnabled;
    private String mCachedSimPin;

    // True if a wired headset is currently plugged in, based on the state
    // from the latest Intent.ACTION_HEADSET_PLUG broadcast we received in
    // mReceiver.onReceive().
    private boolean mIsHeadsetPlugged;

    // True if the keyboard is currently *not* hidden
    // Gets updated whenever there is a Configuration change
    private boolean mIsHardKeyboardOpen;

    // True if we are beginning a call, but the phone state has not changed yet
    private boolean mBeginningCall;

    // Last phone state seen by updatePhoneState()
    Phone.State mLastPhoneState = Phone.State.IDLE;

    private WakeState mWakeState = WakeState.SLEEP;
    private ScreenTimeoutDuration mScreenTimeoutDuration = ScreenTimeoutDuration.DEFAULT;
    private boolean mIgnoreTouchUserActivity = false;
    private IBinder mPokeLockToken = new Binder();
    private IPowerManager mPowerManagerService;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mPartialWakeLock;
    private PowerManager.WakeLock mProximityWakeLock;
    private KeyguardManager mKeyguardManager;
    private StatusBarManager mStatusBarManager;
    private int mStatusBarDisableCount;
    private AccelerometerListener mAccelerometerListener;
    private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private final BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private final BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /** boolean indicating restoring mute state on InCallScreen.onResume() */
    private boolean mShouldRestoreMuteOnInCallResume;

    // Following are the CDMA OTA information Objects used during OTA Call.
    // cdmaOtaProvisionData object store static OTA information that needs
    // to be maintained even during Slider open/close scenarios.
    // cdmaOtaConfigData object stores configuration info to control visiblity
    // of each OTA Screens.
    // cdmaOtaScreenState object store OTA Screen State information.
    public OtaUtils.CdmaOtaProvisionData cdmaOtaProvisionData;
    public OtaUtils.CdmaOtaConfigData cdmaOtaConfigData;
    public OtaUtils.CdmaOtaScreenState cdmaOtaScreenState;
    public OtaUtils.CdmaOtaInCallScreenUiState cdmaOtaInCallScreenUiState;

    // TTY feature enabled on this platform
    private boolean mTtyEnabled;
    // Current TTY operating mode selected by user
    private int mPreferredTtyMode = Phone.TTY_MODE_OFF;
    private int mPhoneType;

    // add by cytown
    private static final String ACTION_VIBRATE_45 = "com.android.phone.PhoneApp.ACTION_VIBRATE_45";
    private CallFeaturesSetting mSettings;
    private static PendingIntent mVibrateIntent;
    private static Vibrator mVibrator = null;
    private static AlarmManager mAM;

    //for adding to Blacklist from call log 
    private static final String INSERT_BLACKLIST = "com.android.phone.INSERT_BLACKLIST";

    public void startVib45(long callDurationMsec) {
        if (VDBG) Log.i(LOG_TAG, "vibrate start @" + callDurationMsec);
        stopVib45();
        long nextalarm = SystemClock.elapsedRealtime() + ((callDurationMsec > 45000) ? 45000 + 60000 - callDurationMsec : 45000 - callDurationMsec);
        if (VDBG) Log.i(LOG_TAG, "am at: " + nextalarm);
        mAM.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextalarm, mVibrateIntent);
    }
    public void stopVib45() {
        if (VDBG) Log.i(LOG_TAG, "vibrate stop @" + SystemClock.elapsedRealtime());
        mAM.cancel(mVibrateIntent);
    }
    private final class TriVibRunnable implements Runnable {
        private int v1, p1, v2;
        TriVibRunnable(int a, int b, int c) {
            v1 = a; p1 = b; v2 = c;
        }
        public void run() {
            if (DBG) Log.i(LOG_TAG, "vibrate " + v1 + ":" + p1 + ":" + v2);
            if (v1 > 0) mVibrator.vibrate(v1);
            if (p1 > 0) SystemClock.sleep(p1);
            if (v2 > 0) mVibrator.vibrate(v2);
        }
    }
    public void vibrate(int v1, int p1, int v2) {
        new Handler().post(new TriVibRunnable(v1, p1, v2));
    }

    /**
     * Set the restore mute state flag. Used when we are setting the mute state
     * OUTSIDE of user interaction {@link PhoneUtils#startNewCall(Phone)}
     */
    /*package*/void setRestoreMuteOnInCallResume (boolean mode) {
        mShouldRestoreMuteOnInCallResume = mode;
    }

    /**
     * Get the restore mute state flag.
     * This is used by the InCallScreen {@link InCallScreen#onResume()} to figure
     * out if we need to restore the mute state for the current active call.
     */
    /*package*/boolean getRestoreMuteOnInCallResume () {
        return mShouldRestoreMuteOnInCallResume;
    }

    /*package*/void checkPhoneType() {
        if (mPhoneType != phone.getPhoneType()) {
            Log.d(LOG_TAG,"handleMessage: radio Technology has changed (" + phone.getPhoneName() + ")");
            initForNewRadioTechnology();
            mPhoneType = phone.getPhoneType();
        }
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Phone.State phoneState;
            checkPhoneType();
            switch (msg.what) {
                // Starts the SIP service. It's a no-op if SIP API is not supported
                // on the deivce.
                // TODO: Having the phone process host the SIP service is only
                // temporary. Will move it to a persistent communication process
                // later.
                case EVENT_START_SIP_SERVICE:
                    SipService.start(getApplicationContext());
                    break;

                // TODO: This event should be handled by the lock screen, just
                // like the "SIM missing" and "Sim locked" cases (bug 1804111).
                case EVENT_SIM_NETWORK_LOCKED:
                    if (getResources().getBoolean(R.bool.ignore_sim_network_locked_events)) {
                        // Some products don't have the concept of a "SIM network lock"
                        Log.i(LOG_TAG, "Ignoring EVENT_SIM_NETWORK_LOCKED event; "
                              + "not showing 'SIM network unlock' PIN entry screen");
                    } else {
                        // Normal case: show the "SIM network unlock" PIN entry screen.
                        // The user won't be able to do anything else until
                        // they enter a valid SIM network PIN.
                        Log.i(LOG_TAG, "show sim depersonal panel");
                        IccNetworkDepersonalizationPanel ndpPanel =
                                new IccNetworkDepersonalizationPanel(PhoneApp.getInstance());
                        ndpPanel.show();
                    }
                    break;

                case EVENT_UPDATE_INCALL_NOTIFICATION:
                    // Tell the NotificationMgr to update the "ongoing
                    // call" icon in the status bar, if necessary.
                    // Currently, this is triggered by a bluetooth headset
                    // state change (since the status bar icon needs to
                    // turn blue when bluetooth is active.)
                    if (DBG) Log.d (LOG_TAG, "- updating in-call notification from handler...");
                    NotificationMgr.getDefault().updateInCallNotification();
                    break;

                case EVENT_DATA_ROAMING_DISCONNECTED:
                    NotificationMgr.getDefault().showDataDisconnectedRoaming();
                    break;

                case EVENT_DATA_ROAMING_OK:
                    NotificationMgr.getDefault().hideDataDisconnectedRoaming();
                    break;

                case MMI_COMPLETE:
                    onMMIComplete((AsyncResult) msg.obj);
                    break;

                case MMI_CANCEL:
                    PhoneUtils.cancelMmiCode(phone);
                    break;

                case EVENT_WIRED_HEADSET_PLUG:
                    // Since the presence of a wired headset or bluetooth affects the
                    // speakerphone, update the "speaker" state.  We ONLY want to do
                    // this on the wired headset connect / disconnect events for now
                    // though, so we're only triggering on EVENT_WIRED_HEADSET_PLUG.

                    phoneState = mCM.getState();
                    // Do not change speaker state if phone is not off hook
                    if (phoneState == Phone.State.OFFHOOK) {
                        if (mBtHandsfree == null || !mBtHandsfree.isAudioOn()) {
                            if (!isHeadsetPlugged()) {
                                // if the state is "not connected", restore the speaker state.
                                PhoneUtils.restoreSpeakerMode(getApplicationContext());
                            } else {
                                // if the state is "connected", force the speaker off without
                                // storing the state.
                                PhoneUtils.turnOnSpeaker(getApplicationContext(), false, false);
                            }
                        }
                    }
                    // Update the Proximity sensor based on headset state
                    updateProximitySensorMode(phoneState);

                    // Force TTY state update according to new headset state
                    if (mTtyEnabled) {
                        sendMessage(obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
                    }
                    break;

                case EVENT_SIM_STATE_CHANGED:
                    // Marks the event where the SIM goes into ready state.
                    // Right now, this is only used for the PUK-unlocking
                    // process.
                    if (msg.obj.equals(IccCard.INTENT_VALUE_ICC_READY)) {
                        // when the right event is triggered and there
                        // are UI objects in the foreground, we close
                        // them to display the lock panel.
                        if (mPUKEntryActivity != null) {
                            mPUKEntryActivity.finish();
                            mPUKEntryActivity = null;
                        }
                        if (mPUKEntryProgressDialog != null) {
                            mPUKEntryProgressDialog.dismiss();
                            mPUKEntryProgressDialog = null;
                        }
                    }
                    break;

                case EVENT_UNSOL_CDMA_INFO_RECORD:
                    //TODO: handle message here;
                    break;

                case EVENT_DOCK_STATE_CHANGED:
                    // If the phone is docked/undocked during a call, and no wired or BT headset
                    // is connected: turn on/off the speaker accordingly.
                    boolean inDockMode = false;
                    if (mDockState == Intent.EXTRA_DOCK_STATE_DESK ||
                            mDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                        inDockMode = true;
                    }
                    if (VDBG) Log.d(LOG_TAG, "received EVENT_DOCK_STATE_CHANGED. Phone inDock = "
                            + inDockMode);

                    phoneState = mCM.getState();
                    if (phoneState == Phone.State.OFFHOOK &&
                            !isHeadsetPlugged() &&
                            !(mBtHandsfree != null && mBtHandsfree.isAudioOn())) {
                        PhoneUtils.turnOnSpeaker(getApplicationContext(), inDockMode, true);

                        if (mInCallScreen != null) {
                            mInCallScreen.requestUpdateTouchUi();
                        }
                    }

                case EVENT_TTY_PREFERRED_MODE_CHANGED:
                    // TTY mode is only applied if a headset is connected
                    int ttyMode;
                    if (isHeadsetPlugged()) {
                        ttyMode = mPreferredTtyMode;
                    } else {
                        ttyMode = Phone.TTY_MODE_OFF;
                    }
                    phone.setTTYMode(ttyMode, mHandler.obtainMessage(EVENT_TTY_MODE_SET));
                    break;

                case EVENT_TTY_MODE_GET:
                    handleQueryTTYModeResponse(msg);
                    break;

                case EVENT_TTY_MODE_SET:
                    handleSetTTYModeResponse(msg);
                    break;

                case EVENT_TECHNOLOGY_CHANGED:
                    // Nothing to do here. already handled by checkPhoneType above
                    break;
            }
        }
    };

    public PhoneApp() {
        sMe = this;
    }

    @Override
    public void onCreate() {
        if (VDBG) Log.v(LOG_TAG, "onCreate()...");

        ContentResolver resolver = getContentResolver();

        if (phone == null) {
            // Initialize the telephony framework
            PhoneFactory.makeDefaultPhones(this);

            // Get the default phone
            phone = PhoneFactory.getDefaultPhone();

            mCM = CallManager.getInstance();
            mCM.registerPhone(phone);


            mPhoneType = phone.getPhoneType();
            NotificationMgr.init(this);

            phoneMgr = new PhoneInterfaceManager(this, phone);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                mBtHandsfree = new BluetoothHandsfree(this, mCM);
                startService(new Intent(this, BluetoothHeadsetService.class));
            } else {
                // Device is not bluetooth capable
                mBtHandsfree = null;
            }

            ringer = new Ringer(this);

            // before registering for phone state changes
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                    LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // Wake lock used to control proximity sensor behavior.
            if ((pm.getSupportedWakeLockFlags()
                 & PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) != 0x0) {
                mProximityWakeLock =
                        pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG);
            }
            if (DBG) Log.d(LOG_TAG, "onCreate: mProximityWakeLock: " + mProximityWakeLock);

            // create mAccelerometerListener only if we are using the proximity sensor
            if (proximitySensorModeEnabled()) {
                mAccelerometerListener = new AccelerometerListener(this, this);
            }

            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            mStatusBarManager = (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            notifier = new CallNotifier(this, phone, ringer, mBtHandsfree, new CallLogAsync());

            // register for ICC status
            IccCard sim = phone.getIccCard();
            if (sim != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC status");
                sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);

            // Read platform settings for TTY feature
            mTtyEnabled = getResources().getBoolean(R.bool.tty_enabled);

            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
            intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
            intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
            intentFilter.addAction(ACTION_VIBRATE_45);
            intentFilter.addAction(INSERT_BLACKLIST);
	    if (mTtyEnabled) {
                intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            }
            intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            registerReceiver(mReceiver, intentFilter);

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            //
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);

            //set the default values for the preferences in the phone.
            PreferenceManager.setDefaultValues(this, R.xml.network_setting, false);

            PreferenceManager.setDefaultValues(this, R.xml.call_feature_setting, false);

            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
            PhoneUtils.setAudioMode(mCM);
        }

        boolean phoneIsCdma = (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA);

        if (phoneIsCdma) {
            cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://icc/adn"));

        // start with the default value to set the mute state.
        mShouldRestoreMuteOnInCallResume = false;

        // add by cytown
        mSettings = CallFeaturesSetting.getInstance(PreferenceManager.getDefaultSharedPreferences(this));
        if (mVibrator == null) {
            mVibrator = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
            mAM = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
            mVibrateIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_VIBRATE_45), 0);
        }

        // TODO: Register for Cdma Information Records
        // phone.registerCdmaInformationRecord(mHandler, EVENT_UNSOL_CDMA_INFO_RECORD, null);

        // Read TTY settings and store it into BP NV.
        // AP owns (i.e. stores) the TTY setting in AP settings database and pushes the setting
        // to BP at power up (BP does not need to make the TTY setting persistent storage).
        // This way, there is a single owner (i.e AP) for the TTY setting in the phone.
        if (mTtyEnabled) {
            mPreferredTtyMode = android.provider.Settings.Secure.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
        }
        // Read HAC settings and configure audio hardware
        if (getResources().getBoolean(R.bool.hac_enabled)) {
            int hac = android.provider.Settings.System.getInt(phone.getContext().getContentResolver(),
                                                              android.provider.Settings.System.HEARING_AID,
                                                              0);
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameter(CallFeaturesSetting.HAC_KEY, hac != 0 ?
                                      CallFeaturesSetting.HAC_VAL_ON :
                                      CallFeaturesSetting.HAC_VAL_OFF);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            mIsHardKeyboardOpen = true;
        } else {
            mIsHardKeyboardOpen = false;
        }

        // Update the Proximity sensor based on keyboard state
        updateProximitySensorMode(mCM.getState());
        super.onConfigurationChanged(newConfig);
    }

    /**
     * Returns the singleton instance of the PhoneApp.
     */
    static PhoneApp getInstance() {
        return sMe;
    }

    /**
     * Returns the Phone associated with this instance
     */
    static Phone getPhone() {
        return getInstance().phone;
    }

    CallFeaturesSetting getSettings() {
        return mSettings;
    }

    Ringer getRinger() {
        return ringer;
    }

    BluetoothHandsfree getBluetoothHandsfree() {
        return mBtHandsfree;
    }

    static Intent createCallLogIntent() {
        Intent  intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }

    /**
     * Return an Intent that can be used to bring up the in-call screen.
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    /* package */ static Intent createInCallIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.phone", getCallScreenClassName());
        return intent;
    }

    /**
     * Variation of createInCallIntent() that also specifies whether the
     * DTMF dialpad should be initially visible when the InCallScreen
     * comes up.
     */
    /* package */ static Intent createInCallIntent(boolean showDialpad) {
        Intent intent = createInCallIntent();
        intent.putExtra(InCallScreen.SHOW_DIALPAD_EXTRA, showDialpad);
        return intent;
    }

    static String getCallScreenClassName() {
        return InCallScreen.class.getName();
    }

    /**
     * Starts the InCallScreen Activity.
     */
    private void displayCallScreen() {
        if (VDBG) Log.d(LOG_TAG, "displayCallScreen()...");
        startActivity(createInCallIntent());
        Profiler.callScreenRequested();
    }

    boolean isSimPinEnabled() {
        return mIsSimPinEnabled;
    }

    boolean authenticateAgainstCachedSimPin(String pin) {
        return (mCachedSimPin != null && mCachedSimPin.equals(pin));
    }

    void setCachedSimPin(String pin) {
        mCachedSimPin = pin;
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    /**
     * @return true if the in-call UI is running as the foreground
     * activity.  (In other words, from the perspective of the
     * InCallScreen activity, return true between onResume() and
     * onPause().)
     *
     * Note this method will return false if the screen is currently off,
     * even if the InCallScreen *was* in the foreground just before the
     * screen turned off.  (This is because the foreground activity is
     * always "paused" while the screen is off.)
     */
    boolean isShowingCallScreen() {
        if (mInCallScreen == null) return false;
        return mInCallScreen.isForegroundActivity();
    }

    /**
     * Dismisses the in-call UI.
     *
     * This also ensures that you won't be able to get back to the in-call
     * UI via the BACK button (since this call removes the InCallScreen
     * from the activity history.)
     * For OTA Call, it call InCallScreen api to handle OTA Call End scenario
     * to display OTA Call End screen.
     */
    void dismissCallScreen() {
        if (mInCallScreen != null) {
            if ((phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) &&
                    (mInCallScreen.isOtaCallInActiveState()
                    || mInCallScreen.isOtaCallInEndState()
                    || ((cdmaOtaScreenState != null)
                    && (cdmaOtaScreenState.otaScreenState
                            != CdmaOtaScreenState.OtaScreenState.OTA_STATUS_UNDEFINED)))) {
                // TODO: During OTA Call, display should not become dark to
                // allow user to see OTA UI update. Phone app needs to hold
                // a SCREEN_DIM_WAKE_LOCK wake lock during the entire OTA call.
                wakeUpScreen();
                // If InCallScreen is not in foreground we resume it to show the OTA call end screen
                // Fire off the InCallScreen intent
                displayCallScreen();

                mInCallScreen.handleOtaCallEnd();
                return;
            } else {
                mInCallScreen.finish();
            }
        }
    }

    /**
     * Handle OTA events
     *
     * When OTA call is active and display becomes dark, then CallNotifier will
     * handle OTA Events by calling this api which then calls OtaUtil function.
     */
    void handleOtaEvents(Message msg) {

        if (DBG) Log.d(LOG_TAG, "Enter handleOtaEvents");
        if ((mInCallScreen != null) && (!isShowingCallScreen())) {
            if (mInCallScreen.otaUtils != null) {
                mInCallScreen.otaUtils.onOtaProvisionStatusChanged((AsyncResult) msg.obj);
            }
        }
    }


    /**
     * Sets the activity responsible for un-PUK-blocking the device
     * so that we may close it when we receive a positive result.
     * mPUKEntryActivity is also used to indicate to the device that
     * we are trying to un-PUK-lock the phone. In other words, iff
     * it is NOT null, then we are trying to unlock and waiting for
     * the SIM to move to READY state.
     *
     * @param activity is the activity to close when PUK has
     * finished unlocking. Can be set to null to indicate the unlock
     * or SIM READYing process is over.
     */
    void setPukEntryActivity(Activity activity) {
        mPUKEntryActivity = activity;
    }

    Activity getPUKEntryActivity() {
        return mPUKEntryActivity;
    }

    /**
     * Sets the dialog responsible for notifying the user of un-PUK-
     * blocking - SIM READYing progress, so that we may dismiss it
     * when we receive a positive result.
     *
     * @param dialog indicates the progress dialog informing the user
     * of the state of the device.  Dismissed upon completion of
     * READYing process
     */
    void setPukEntryProgressDialog(ProgressDialog dialog) {
        mPUKEntryProgressDialog = dialog;
    }

    ProgressDialog getPUKEntryProgressDialog() {
        return mPUKEntryProgressDialog;
    }

    /**
     * Disables the status bar.  This is used by the phone app when in-call UI is active.
     *
     * Any call to this method MUST be followed (eventually)
     * by a corresponding reenableStatusBar() call.
     */
    /* package */ void disableStatusBar() {
        if (DBG) Log.d(LOG_TAG, "disable status bar");
        synchronized (this) {
            if (mStatusBarDisableCount++ == 0) {
                if (DBG)  Log.d(LOG_TAG, "StatusBarManager.DISABLE_EXPAND");
                mStatusBarManager.disable(StatusBarManager.DISABLE_EXPAND);
            }
        }
    }

    /**
     * Re-enables the status bar after a previous disableStatusBar() call.
     *
     * Any call to this method MUST correspond to (i.e. be balanced with)
     * a previous disableStatusBar() call.
     */
    /* package */ void reenableStatusBar() {
        if (DBG) Log.d(LOG_TAG, "re-enable status bar");
        synchronized (this) {
            if (mStatusBarDisableCount > 0) {
                if (--mStatusBarDisableCount == 0) {
                    if (DBG) Log.d(LOG_TAG, "StatusBarManager.DISABLE_NONE");
                    mStatusBarManager.disable(StatusBarManager.DISABLE_NONE);
                }
            } else {
                Log.e(LOG_TAG, "mStatusBarDisableCount is already zero");
            }
        }
    }

    /**
     * Controls how quickly the screen times out.
     *
     * The poke lock controls how long it takes before the screen powers
     * down, and therefore has no immediate effect when the current
     * WakeState (see {@link PhoneApp#requestWakeState}) is FULL.
     * If we're in a state where the screen *is* allowed to turn off,
     * though, the poke lock will determine the timeout interval (long or
     * short).
     *
     * @param shortPokeLock tells the device the timeout duration to use
     * before going to sleep
     * {@link com.android.server.PowerManagerService#SHORT_KEYLIGHT_DELAY}.
     */
    /* package */ void setScreenTimeout(ScreenTimeoutDuration duration) {
        if (VDBG) Log.d(LOG_TAG, "setScreenTimeout(" + duration + ")...");

        // make sure we don't set the poke lock repeatedly so that we
        // avoid triggering the userActivity calls in
        // PowerManagerService.setPokeLock().
        if (duration == mScreenTimeoutDuration) {
            return;
        }
        // stick with default timeout if we are using the proximity sensor
        if (proximitySensorModeEnabled()) {
            return;
        }
        mScreenTimeoutDuration = duration;
        updatePokeLock();
    }

    /**
     * Update the state of the poke lock held by the phone app,
     * based on the current desired screen timeout and the
     * current "ignore user activity on touch" flag.
     */
    private void updatePokeLock() {
        // This is kind of convoluted, but the basic thing to remember is
        // that the poke lock just sends a message to the screen to tell
        // it to stay on for a while.
        // The default is 0, for a long timeout and should be set that way
        // when we are heading back into a the keyguard / screen off
        // state, and also when we're trying to keep the screen alive
        // while ringing.  We'll also want to ignore the cheek events
        // regardless of the timeout duration.
        // The short timeout is really used whenever we want to give up
        // the screen lock, such as when we're in call.
        int pokeLockSetting = LocalPowerManager.POKE_LOCK_IGNORE_CHEEK_EVENTS;
        switch (mScreenTimeoutDuration) {
            case SHORT:
                // Set the poke lock to timeout the display after a short
                // timeout (5s). This ensures that the screen goes to sleep
                // as soon as acceptably possible after we the wake lock
                // has been released.
                //                pokeLockSetting |= LocalPowerManager.POKE_LOCK_SHORT_TIMEOUT;
                pokeLockSetting |= mSettings.mScreenAwake ? LocalPowerManager.POKE_LOCK_MEDIUM_TIMEOUT : LocalPowerManager.POKE_LOCK_SHORT_TIMEOUT;
                break;

            case MEDIUM:
                // Set the poke lock to timeout the display after a medium
                // timeout (15s). This ensures that the screen goes to sleep
                // as soon as acceptably possible after we the wake lock
                // has been released.
                pokeLockSetting |= LocalPowerManager.POKE_LOCK_MEDIUM_TIMEOUT;
                break;

            case DEFAULT:
            default:
                // set the poke lock to timeout the display after a long
                // delay by default.
                // TODO: it may be nice to be able to disable cheek presses
                // for long poke locks (emergency dialer, for instance).
                break;
        }

        if (mIgnoreTouchUserActivity) {
            pokeLockSetting |= LocalPowerManager.POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS;
        }

        // Send the request
        try {
            mPowerManagerService.setPokeLock(pokeLockSetting, mPokeLockToken, LOG_TAG);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "mPowerManagerService.setPokeLock() failed: " + e);
        }
    }

    /**
     * Controls whether or not the screen is allowed to sleep.
     *
     * Once sleep is allowed (WakeState is SLEEP), it will rely on the
     * settings for the poke lock to determine when to timeout and let
     * the device sleep {@link PhoneApp#setScreenTimeout}.
     *
     * @param ws tells the device to how to wake.
     */
    /* package */ void requestWakeState(WakeState ws) {
        if (VDBG) Log.d(LOG_TAG, "requestWakeState(" + ws + ")...");
        synchronized (this) {
            if (mWakeState != ws) {
                switch (ws) {
                    case PARTIAL:
                        // acquire the processor wake lock, and release the FULL
                        // lock if it is being held.
                        mPartialWakeLock.acquire();
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        break;
                    case FULL:
                        // acquire the full wake lock, and release the PARTIAL
                        // lock if it is being held.
                        mWakeLock.acquire();
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                    case SLEEP:
                    default:
                        // release both the PARTIAL and FULL locks.
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                }
                mWakeState = ws;
            }
        }
    }

    /**
     * If we are not currently keeping the screen on, then poke the power
     * manager to wake up the screen for the user activity timeout duration.
     */
    /* package */ void wakeUpScreen() {
        synchronized (this) {
            if (mWakeState == WakeState.SLEEP) {
                if (DBG) Log.d(LOG_TAG, "pulse screen lock");
                try {
                    mPowerManagerService.userActivityWithForce(SystemClock.uptimeMillis(), false, true);
                } catch (RemoteException ex) {
                    // Ignore -- the system process is dead.
                }
            }
        }
    }

    /**
     * Sets the wake state and screen timeout based on the current state
     * of the phone, and the current state of the in-call UI.
     *
     * This method is a "UI Policy" wrapper around
     * {@link PhoneApp#requestWakeState} and {@link PhoneApp#setScreenTimeout}.
     *
     * It's safe to call this method regardless of the state of the Phone
     * (e.g. whether or not it's idle), and regardless of the state of the
     * Phone UI (e.g. whether or not the InCallScreen is active.)
     */
    /* package */ void updateWakeState() {
        Phone.State state = mCM.getState();

        // True if the in-call UI is the foreground activity.
        // (Note this will be false if the screen is currently off,
        // since in that case *no* activity is in the foreground.)
        boolean isShowingCallScreen = isShowingCallScreen();

        // True if the InCallScreen's DTMF dialer is currently opened.
        // (Note this does NOT imply whether or not the InCallScreen
        // itself is visible.)
        boolean isDialerOpened = (mInCallScreen != null) && mInCallScreen.isDialerOpened();

        // True if the speakerphone is in use.  (If so, we *always* use
        // the default timeout.  Since the user is obviously not holding
        // the phone up to his/her face, we don't need to worry about
        // false touches, and thus don't need to turn the screen off so
        // aggressively.)
        // Note that we need to make a fresh call to this method any
        // time the speaker state changes.  (That happens in
        // PhoneUtils.turnOnSpeaker().)
        boolean isSpeakerInUse = (state == Phone.State.OFFHOOK) && PhoneUtils.isSpeakerOn(this);

        // TODO (bug 1440854): The screen timeout *might* also need to
        // depend on the bluetooth state, but this isn't as clear-cut as
        // the speaker state (since while using BT it's common for the
        // user to put the phone straight into a pocket, in which case the
        // timeout should probably still be short.)

        if (DBG) Log.d(LOG_TAG, "updateWakeState: callscreen " + isShowingCallScreen
                       + ", dialer " + isDialerOpened
                       + ", speaker " + isSpeakerInUse + "...");

        //
        // (1) Set the screen timeout.
        //
        // Note that the "screen timeout" value we determine here is
        // meaningless if the screen is forced on (see (2) below.)
        //
        if (!isShowingCallScreen || isSpeakerInUse) {
            // Use the system-wide default timeout.
            setScreenTimeout(ScreenTimeoutDuration.DEFAULT);
        } else {
            // We're on the in-call screen, and *not* using the speakerphone.
            if (isDialerOpened) {
                // The DTMF dialpad is up.  This case is special because
                // the in-call UI has its own "touch lock" mechanism to
                // disable the dialpad after a very short amount of idle
                // time (to avoid false touches from the user's face while
                // in-call.)
                //
                // In this case the *physical* screen just uses the
                // system-wide default timeout.
                setScreenTimeout(ScreenTimeoutDuration.DEFAULT);
            } else {
                // We're on the in-call screen, and not using the DTMF dialpad.
                // There's actually no touchable UI onscreen at all in
                // this state.  Also, the user is (most likely) not
                // looking at the screen at all, since they're probably
                // holding the phone up to their face.  Here we use a
                // special screen timeout value specific to the in-call
                // screen, purely to save battery life.

                //                setScreenTimeout(ScreenTimeoutDuration.MEDIUM);
                setScreenTimeout(mSettings.mScreenAwake ? ScreenTimeoutDuration.DEFAULT : ScreenTimeoutDuration.MEDIUM);
            }
        }

        //
        // (2) Decide whether to force the screen on or not.
        //
        // Force the screen to be on if the phone is ringing or dialing,
        // or if we're displaying the "Call ended" UI for a connection in
        // the "disconnected" state.
        //
        boolean isRinging = (state == Phone.State.RINGING);
        boolean isDialing = (phone.getForegroundCall().getState() == Call.State.DIALING);
        boolean showingDisconnectedConnection =
                PhoneUtils.hasDisconnectedConnections(phone) && isShowingCallScreen;
        boolean keepScreenOn = isRinging || isDialing || showingDisconnectedConnection;
        if (DBG) Log.d(LOG_TAG, "updateWakeState: keepScreenOn = " + keepScreenOn
                       + " (isRinging " + isRinging
                       + ", isDialing " + isDialing
                       + ", showingDisc " + showingDisconnectedConnection + ")");
        // keepScreenOn == true means we'll hold a full wake lock:
        requestWakeState(keepScreenOn ? WakeState.FULL : WakeState.SLEEP);
    }

    /**
     * Wrapper around the PowerManagerService.preventScreenOn() API.
     * This allows the in-call UI to prevent the screen from turning on
     * even if a subsequent call to updateWakeState() causes us to acquire
     * a full wake lock.
     */
    /* package */ void preventScreenOn(boolean prevent) {
        if (VDBG) Log.d(LOG_TAG, "- preventScreenOn(" + prevent + ")...");
        try {
            mPowerManagerService.preventScreenOn(prevent);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "mPowerManagerService.preventScreenOn() failed: " + e);
        }
    }

    /**
     * Sets or clears the flag that tells the PowerManager that touch
     * (and cheek) events should NOT be considered "user activity".
     *
     * Since the in-call UI is totally insensitive to touch in most
     * states, we set this flag whenever the InCallScreen is in the
     * foreground.  (Otherwise, repeated unintentional touches could
     * prevent the device from going to sleep.)
     *
     * There *are* some some touch events that really do count as user
     * activity, though.  For those, we need to manually poke the
     * PowerManager's userActivity method; see pokeUserActivity().
     */
    /* package */ void setIgnoreTouchUserActivity(boolean ignore) {
        if (VDBG) Log.d(LOG_TAG, "setIgnoreTouchUserActivity(" + ignore + ")...");
        mIgnoreTouchUserActivity = ignore;
        updatePokeLock();
    }

    /**
     * Manually pokes the PowerManager's userActivity method.  Since we
     * hold the POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS poke lock while
     * the InCallScreen is active, we need to do this for touch events
     * that really do count as user activity (like DTMF key presses, or
     * unlocking the "touch lock" overlay.)
     */
    /* package */ void pokeUserActivity() {
        if (VDBG) Log.d(LOG_TAG, "pokeUserActivity()...");
        try {
            mPowerManagerService.userActivity(SystemClock.uptimeMillis(), false);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "mPowerManagerService.userActivity() failed: " + e);
        }
    }

    /**
     * Set when a new outgoing call is beginning, so we can update
     * the proximity sensor state.
     * Cleared when the InCallScreen is no longer in the foreground,
     * in case the call fails without changing the telephony state.
     */
    /* package */ void setBeginningCall(boolean beginning) {
        // Note that we are beginning a new call, for proximity sensor support
        mBeginningCall = beginning;
        // Update the Proximity sensor based on mBeginningCall state
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * Updates the wake lock used to control proximity sensor behavior,
     * based on the current state of the phone.  This method is called
     * from the CallNotifier on any phone state change.
     *
     * On devices that have a proximity sensor, to avoid false touches
     * during a call, we hold a PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock
     * whenever the phone is off hook.  (When held, that wake lock causes
     * the screen to turn off automatically when the sensor detects an
     * object close to the screen.)
     *
     * This method is a no-op for devices that don't have a proximity
     * sensor.
     *
     * Note this method doesn't care if the InCallScreen is the foreground
     * activity or not.  That's because we want the proximity sensor to be
     * enabled any time the phone is in use, to avoid false cheek events
     * for whatever app you happen to be running.
     *
     * Proximity wake lock will *not* be held if any one of the
     * conditions is true while on a call:
     * 1) If the audio is routed via Bluetooth
     * 2) If a wired headset is connected
     * 3) if the speaker is ON
     * 4) If the slider is open(i.e. the hardkeyboard is *not* hidden)
     *
     * @param state current state of the phone (see {@link Phone#State})
     */
    /* package */ void updateProximitySensorMode(Phone.State state) {
        if (VDBG) Log.d(LOG_TAG, "updateProximitySensorMode: state = " + state);

        if (proximitySensorModeEnabled()) {
            synchronized (mProximityWakeLock) {
                // turn proximity sensor off and turn screen on immediately if
                // we are using a headset, the keyboard is open, or the device
                // is being held in a horizontal position.
                boolean screenOnImmediately = (isHeadsetPlugged()
                            || PhoneUtils.isSpeakerOn(this)
                            || ((mBtHandsfree != null) && mBtHandsfree.isAudioOn())
                            || mIsHardKeyboardOpen);
                // We do not keep the screen off when we are horizontal, but we do not force it
                // on when we become horizontal until the proximity sensor goes negative.
                boolean horizontal = (mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL);

                if (((state == Phone.State.OFFHOOK) || mBeginningCall) &&
                        !screenOnImmediately && !horizontal) {
                    // Phone is in use!  Arrange for the screen to turn off
                    // automatically when the sensor detects a close object.
                    if (!mProximityWakeLock.isHeld()) {
                        if (DBG) Log.d(LOG_TAG, "updateProximitySensorMode: acquiring...");
                        mProximityWakeLock.acquire();
                    } else {
                        if (VDBG) Log.d(LOG_TAG, "updateProximitySensorMode: lock already held.");
                    }
                } else {
                    // Phone is either idle, or ringing.  We don't want any
                    // special proximity sensor behavior in either case.
                    if (mProximityWakeLock.isHeld()) {
                        if (DBG) Log.d(LOG_TAG, "updateProximitySensorMode: releasing...");
                        // Wait until user has moved the phone away from his head if we are
                        // releasing due to the phone call ending.
                        // Qtherwise, turn screen on immediately
                        int flags =
                            (screenOnImmediately ? 0 : PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE);
                        mProximityWakeLock.release(flags);
                    } else {
                        if (VDBG) {
                            Log.d(LOG_TAG, "updateProximitySensorMode: lock already released.");
                        }
                    }
                }
            }
        }
    }

    public void orientationChanged(int orientation) {
        mOrientation = orientation;
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * Notifies the phone app when the phone state changes.
     * Currently used only for proximity sensor support.
     */
    /* package */ void updatePhoneState(Phone.State state) {
        if (state != mLastPhoneState) {
            mLastPhoneState = state;
            updateProximitySensorMode(state);
            if (mAccelerometerListener != null) {
                // use accelerometer to augment proximity sensor when in call
                mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
                mAccelerometerListener.enable(!mSettings.mAlwaysProximity &&
                        state == Phone.State.OFFHOOK);
            }
            // clear our beginning call flag
            mBeginningCall = false;
            // While we are in call, the in-call screen should dismiss the keyguard.
            // This allows the user to press Home to go directly home without going through
            // an insecure lock screen.
            // But we do not want to do this if there is no active call so we do not
            // bypass the keyguard if the call is not answered or declined.
            if (mInCallScreen != null) {
                mInCallScreen.updateKeyguardPolicy(state == Phone.State.OFFHOOK);
            }
        }
    }

    /* package */ Phone.State getPhoneState() {
        return mLastPhoneState;
    }

    /**
     * @return true if this device supports the "proximity sensor
     * auto-lock" feature while in-call (see updateProximitySensorMode()).
     */
    /* package */ boolean proximitySensorModeEnabled() {
        return (mProximityWakeLock != null);
    }

    KeyguardManager getKeyguardManager() {
        return mKeyguardManager;
    }

    private void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(phone, getInstance(), mmiCode, null, null);
    }

    private void initForNewRadioTechnology() {
        if (DBG) Log.d(LOG_TAG, "initForNewRadioTechnology...");

        if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            // Create an instance of CdmaPhoneCallState and initialize it to IDLE
            cdmaPhoneCallState = new CdmaPhoneCallState();
            cdmaPhoneCallState.CdmaPhoneCallStateInit();

            //create instances of CDMA OTA data classes
            if (cdmaOtaProvisionData == null) {
                cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            }
            if (cdmaOtaConfigData == null) {
                cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            }
            if (cdmaOtaScreenState == null) {
                cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            }
            if (cdmaOtaInCallScreenUiState == null) {
                cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
            }
        } else {
            //Clean up OTA data in GSM/UMTS. It is valid only for CDMA
            clearOtaState();
        }

        ringer.updateRingerContextAfterRadioTechnologyChange(this.phone);
        notifier.updateCallNotifierRegistrationsAfterRadioTechnologyChange();
        if (mBtHandsfree != null) {
            mBtHandsfree.updateBtHandsfreeAfterRadioTechnologyChange();
        }
        if (mInCallScreen != null) {
            mInCallScreen.updateAfterRadioTechnologyChange();
        }

        // Update registration for ICC status after radio technology change
        IccCard sim = phone.getIccCard();
        if (sim != null) {
            if (DBG) Log.d(LOG_TAG, "Update registration for ICC status...");

            //Register all events new to the new active phone
            sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
        }
    }


    /**
     * @return true if a wired headset is currently plugged in.
     *
     * @see Intent.ACTION_HEADSET_PLUG (which we listen for in mReceiver.onReceive())
     */
    boolean isHeadsetPlugged() {
        return mIsHeadsetPlugged;
    }

    /**
     * @return true if the onscreen UI should currently be showing the
     * special "bluetooth is active" indication in a couple of places (in
     * which UI elements turn blue and/or show the bluetooth logo.)
     *
     * This depends on the BluetoothHeadset state *and* the current
     * telephony state; see shouldShowBluetoothIndication().
     *
     * @see CallCard
     * @see NotificationMgr.updateInCallNotification
     */
    /* package */ boolean showBluetoothIndication() {
        return mShowBluetoothIndication;
    }

    /**
     * Recomputes the mShowBluetoothIndication flag based on the current
     * bluetooth state and current telephony state.
     *
     * This needs to be called any time the bluetooth headset state or the
     * telephony state changes.
     *
     * @param forceUiUpdate if true, force the UI elements that care
     *                      about this flag to update themselves.
     */
    /* package */ void updateBluetoothIndication(boolean forceUiUpdate) {
        mShowBluetoothIndication = shouldShowBluetoothIndication(mBluetoothHeadsetState,
                                                                 mBluetoothHeadsetAudioState,
                                                                 mCM);
        if (forceUiUpdate) {
            // Post Handler messages to the various components that might
            // need to be refreshed based on the new state.
            if (isShowingCallScreen()) mInCallScreen.requestUpdateBluetoothIndication();
            if (DBG) Log.d (LOG_TAG, "- updating in-call notification for BT state change...");
            mHandler.sendEmptyMessage(EVENT_UPDATE_INCALL_NOTIFICATION);
        }

        // Update the Proximity sensor based on Bluetooth audio state
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * UI policy helper function for the couple of places in the UI that
     * have some way of indicating that "bluetooth is in use."
     *
     * @return true if the onscreen UI should indicate that "bluetooth is in use",
     *         based on the specified bluetooth headset state, and the
     *         current state of the phone.
     * @see showBluetoothIndication()
     */
    private static boolean shouldShowBluetoothIndication(int bluetoothState,
                                                         int bluetoothAudioState,
                                                         CallManager cm) {
        // We want the UI to indicate that "bluetooth is in use" in two
        // slightly different cases:
        //
        // (a) The obvious case: if a bluetooth headset is currently in
        //     use for an ongoing call.
        //
        // (b) The not-so-obvious case: if an incoming call is ringing,
        //     and we expect that audio *will* be routed to a bluetooth
        //     headset once the call is answered.

        switch (cm.getState()) {
            case OFFHOOK:
                // This covers normal active calls, and also the case if
                // the foreground call is DIALING or ALERTING.  In this
                // case, bluetooth is considered "active" if a headset
                // is connected *and* audio is being routed to it.
                return ((bluetoothState == BluetoothHeadset.STATE_CONNECTED)
                        && (bluetoothAudioState == BluetoothHeadset.AUDIO_STATE_CONNECTED));

            case RINGING:
                // If an incoming call is ringing, we're *not* yet routing
                // audio to the headset (since there's no in-call audio
                // yet!)  In this case, if a bluetooth headset is
                // connected at all, we assume that it'll become active
                // once the user answers the phone.
                return (bluetoothState == BluetoothHeadset.STATE_CONNECTED);

            default:  // Presumably IDLE
                return false;
        }
    }


    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class PhoneAppBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                // When airplane mode is selected/deselected from settings
                // AirplaneModeEnabler sets the value of extra "state" to
                // true if airplane mode is enabled and false if it is
                // disabled and broadcasts the intent. setRadioPower uses
                // true if airplane mode is disabled and false if enabled.
                boolean enabled = intent.getBooleanExtra("state",false);
                phone.setRadioPower(!enabled);
            } else if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
                mBluetoothHeadsetState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                                                            BluetoothHeadset.STATE_ERROR);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: HEADSET_STATE_CHANGED_ACTION");
                if (VDBG) Log.d(LOG_TAG, "==> new state: " + mBluetoothHeadsetState);
                updateBluetoothIndication(true);  // Also update any visible UI if necessary
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                mBluetoothHeadsetAudioState =
                        intent.getIntExtra(BluetoothHeadset.EXTRA_AUDIO_STATE,
                                           BluetoothHeadset.STATE_ERROR);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: HEADSET_AUDIO_STATE_CHANGED_ACTION");
                if (VDBG) Log.d(LOG_TAG, "==> new state: " + mBluetoothHeadsetAudioState);
                updateBluetoothIndication(true);  // Also update any visible UI if necessary
            } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED");
                if (VDBG) Log.d(LOG_TAG, "- state: " + intent.getStringExtra(Phone.STATE_KEY));
                if (VDBG) Log.d(LOG_TAG, "- reason: "
                                + intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY));

                // The "data disconnected due to roaming" notification is
                // visible if you've lost data connectivity because you're
                // roaming and you have the "data roaming" feature turned off.
                boolean disconnectedDueToRoaming = false;
                if ("DISCONNECTED".equals(intent.getStringExtra(Phone.STATE_KEY))) {
                    String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                    if (Phone.REASON_ROAMING_ON.equals(reason)) {
                        // We just lost our data connection, and the reason
                        // is that we started roaming.  This implies that
                        // the user has data roaming turned off.
                        disconnectedDueToRoaming = true;
                    }
                }
                mHandler.sendEmptyMessage(disconnectedDueToRoaming
                                          ? EVENT_DATA_ROAMING_DISCONNECTED
                                          : EVENT_DATA_ROAMING_OK);
            } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_HEADSET_PLUG");
                if (VDBG) Log.d(LOG_TAG, "    state: " + intent.getIntExtra("state", 0));
                if (VDBG) Log.d(LOG_TAG, "    name: " + intent.getStringExtra("name"));
                mIsHeadsetPlugged = (intent.getIntExtra("state", 0) == 1);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_WIRED_HEADSET_PLUG, 0));
            } else if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_BATTERY_LOW");
                notifier.sendBatteryLow();  // Play a warning tone if in-call
            } else if ((action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED))) {
                if (mPUKEntryActivity != null) {
                    // if an attempt to un-PUK-lock the device was made, while we're
                    // receiving this state change notification, notify the handler.
                    // NOTE: This is ONLY triggered if an attempt to un-PUK-lock has
                    // been attempted.
                    mHandler.sendMessage(mHandler.obtainMessage(EVENT_SIM_STATE_CHANGED,
                            intent.getStringExtra(IccCard.INTENT_KEY_ICC_STATE)));
                }

                String iccState = intent.getStringExtra(IccCard.INTENT_KEY_ICC_STATE);
                String reason = intent.getStringExtra(IccCard.INTENT_KEY_LOCKED_REASON);
                if ((reason != null) && (IccCard.INTENT_VALUE_ICC_LOCKED.equals(iccState))) {
                    if (getResources().getBoolean(R.bool.ignore_perso_locked_events)) {
                        // Some products don't have the concept of a perso lock.
                        Log.i(LOG_TAG, "Ignoring ICC Perso Locked event "
                              + "not showing Depersonalization screen for PIN entry");
                    } else {
                       showDepersonalizationScreen(reason);
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                String newPhone = intent.getStringExtra(Phone.PHONE_NAME_KEY);
                Log.d(LOG_TAG, "Radio technology switched. Now " + newPhone + " is active.");
                mHandler.sendEmptyMessage(EVENT_TECHNOLOGY_CHANGED);
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                handleServiceStateChanged(intent);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    Log.d(LOG_TAG, "Emergency Callback Mode arrived in PhoneApp.");
                    // Start Emergency Callback Mode service
                    if (intent.getBooleanExtra("phoneinECMState", false)) {
                        context.startService(new Intent(context,
                                EmergencyCallbackModeService.class));
                    }
                } else {
                    Log.e(LOG_TAG, "Error! Emergency Callback Mode not supported for " +
                            phone.getPhoneName() + " phones");
                }
            // Vibrate 45 sec receiver add by cytown
            } else if (action.equals(ACTION_VIBRATE_45)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_VIBRATE_45");
                mAM.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60000, mVibrateIntent);
                if (DBG) Log.i(LOG_TAG, "vibrate on 45 sec");
                mVibrator.vibrate(70);
                SystemClock.sleep(70);
                mVibrator.cancel();
                if (VDBG) Log.d(LOG_TAG, "mReceiver: force vib cancel");
                //vibrate(70, 70, -1);
            } else if (action.equals(Intent.ACTION_DOCK_EVENT)) {
                mDockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (VDBG) Log.d(LOG_TAG, "ACTION_DOCK_EVENT -> mDockState = " + mDockState);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_DOCK_STATE_CHANGED, 0));
            } else if (action.equals(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION)) {
                mPreferredTtyMode = intent.getIntExtra(TtyIntent.TTY_PREFFERED_MODE,
                                                       Phone.TTY_MODE_OFF);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: TTY_PREFERRED_MODE_CHANGE_ACTION");
                if (VDBG) Log.d(LOG_TAG, "    mode: " + mPreferredTtyMode);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
            } else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                int ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE,
                        AudioManager.RINGER_MODE_NORMAL);
                if(ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    notifier.silenceRinger();
                }
            }else if (action.equals(INSERT_BLACKLIST)) {
	    	PhoneApp.getInstance().getSettings().addBlackList(intent.getStringExtra("Insert.BLACKLIST"));
	    }
        }
    }

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in
     * PhoneAppBroadcastReceiver because we instantiate this as a totally
     * separate BroadcastReceiver instance, since we need to manually
     * adjust its IntentFilter's priority (to make sure we get these
     * intents *before* the media player.)
     */
    private class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (VDBG) Log.d(LOG_TAG,
                           "MediaButtonBroadcastReceiver.onReceive()...  event = " + event);
            if ((event != null)
                && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: HEADSETHOOK");
                boolean consumed = PhoneUtils.handleHeadsetHook(phone, event);
                if (VDBG) Log.d(LOG_TAG, "==> handleHeadsetHook(): consumed = " + consumed);
                if (consumed) {
                    // If a headset is attached and the press is consumed, also update
                    // any UI items (such as an InCallScreen mute button) that may need to
                    // be updated if their state changed.
                    if (isShowingCallScreen()) {
                        updateInCallScreenTouchUi();
                    }
                    abortBroadcast();
                }
            } else if (event.getRepeatCount() == 2) {
                // Long press to hang up:
                if (VDBG) Log.d(LOG_TAG, "repeat count = 2 is triggered!");
                boolean consumed = PhoneUtils.handleHeadsetHookHangup(phone);
                if (consumed) {
                    if (VDBG) Log.d(LOG_TAG, "consuming broadcast");
                    abortBroadcast();  // BUG: Media player on/off is toggled sometimes if used to hang up
                }
            } else {
                if (mCM.getState() != Phone.State.IDLE) {
                    // If the phone is anything other than completely idle,
                    // then we consume and ignore any media key events,
                    // Otherwise it is too easy to accidentally start
                    // playing music while a phone call is in progress.
                    if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: consumed");
                    abortBroadcast();
                }
            }
        }
    }

    private void handleServiceStateChanged(Intent intent) {
        /**
         * This used to handle updating EriTextWidgetProvider this routine
         * and and listening for ACTION_SERVICE_STATE_CHANGED intents could
         * be removed. But leaving just in case it might be needed in the near
         * future.
         */

        // If service just returned, start sending out the queued messages
        ServiceState ss = ServiceState.newFromBundle(intent.getExtras());

        boolean hasService = true;
        boolean isCdma = false;
        String eriText = "";

        if (ss != null) {
            int state = ss.getState();
            NotificationMgr.getDefault().updateNetworkSelection(state);
            switch (state) {
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_POWER_OFF:
                    hasService = false;
                    break;
            }
        } else {
            hasService = false;
        }
    }

    public boolean isOtaCallInActiveState() {
        boolean otaCallActive = false;
        if (mInCallScreen != null) {
            otaCallActive = mInCallScreen.isOtaCallInActiveState();
        }
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInActiveState " + otaCallActive);
        return otaCallActive;
    }

    public boolean isOtaCallInEndState() {
        boolean otaCallEnded = false;
        if (mInCallScreen != null) {
            otaCallEnded = mInCallScreen.isOtaCallInEndState();
        }
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInEndState " + otaCallEnded);
        return otaCallEnded;
    }

    // it is safe to call clearOtaState() even if the InCallScreen isn't active
    public void clearOtaState() {
        if (DBG) Log.d(LOG_TAG, "- clearOtaState ...");
        if ((mInCallScreen != null)
                && (mInCallScreen.otaUtils != null)) {
            mInCallScreen.otaUtils.cleanOtaScreen(true);
            if (DBG) Log.d(LOG_TAG, "  - clearOtaState clears OTA screen");
        }
    }

    // it is safe to call dismissOtaDialogs() even if the InCallScreen isn't active
    public void dismissOtaDialogs() {
        if (DBG) Log.d(LOG_TAG, "- dismissOtaDialogs ...");
        if ((mInCallScreen != null)
                && (mInCallScreen.otaUtils != null)) {
            mInCallScreen.otaUtils.dismissAllOtaDialogs();
            if (DBG) Log.d(LOG_TAG, "  - dismissOtaDialogs clears OTA dialogs");
        }
    }

    // it is safe to call clearInCallScreenMode() even if the InCallScreen isn't active
    public void clearInCallScreenMode() {
        if (DBG) Log.d(LOG_TAG, "- clearInCallScreenMode ...");
        if (mInCallScreen != null) {
            mInCallScreen.resetInCallScreenMode();
        }
    }

    // Update InCallScreen's touch UI. It is safe to call even if InCallScreen isn't active
    public void updateInCallScreenTouchUi() {
        if (DBG) Log.d(LOG_TAG, "- updateInCallScreenTouchUi ...");
        if (mInCallScreen != null) {
            mInCallScreen.requestUpdateTouchUi();
        }
    }

    private void handleQueryTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse: Error getting TTY state.");
        } else {
            if (DBG) Log.d(LOG_TAG,
                           "handleQueryTTYModeResponse: TTY enable state successfully queried.");

            int ttymode = ((int[]) ar.result)[0];
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse:ttymode=" + ttymode);

            Intent ttyModeChanged = new Intent(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
            ttyModeChanged.putExtra("ttyEnabled", ttymode != Phone.TTY_MODE_OFF);
            sendBroadcast(ttyModeChanged);

            String audioTtyMode;
            switch (ttymode) {
            case Phone.TTY_MODE_FULL:
                audioTtyMode = "tty_full";
                break;
            case Phone.TTY_MODE_VCO:
                audioTtyMode = "tty_vco";
                break;
            case Phone.TTY_MODE_HCO:
                audioTtyMode = "tty_hco";
                break;
            case Phone.TTY_MODE_OFF:
            default:
                audioTtyMode = "tty_off";
                break;
            }
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameters("tty_mode="+audioTtyMode);
        }
    }

    private void handleSetTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception != null) {
            if (DBG) Log.d (LOG_TAG,
                    "handleSetTTYModeResponse: Error setting TTY mode, ar.exception"
                    + ar.exception);
        }
        phone.queryTTYMode(mHandler.obtainMessage(EVENT_TTY_MODE_GET));
    }

    /* package */ void clearUserActivityTimeout() {
        try {
            mPowerManagerService.clearUserActivityTimeout(SystemClock.uptimeMillis(),
                    10*1000 /* 10 sec */);
        } catch (RemoteException ex) {
            // System process is dead.
        }
    }

    private void showDepersonalizationScreen(String reason) {
        int subtype = IccDepersonalizationConstants.ICC_SIM_NETWORK;

        if(IccCard.INTENT_VALUE_LOCKED_NETWORK.equals(reason)) {
           //Network Depersonalization is presently handled
           //using dedicated Notification.
           Log.i(LOG_TAG,"Ignoring SIM NETWORK Depersonalization "
                 + "since this is handled differently");
        } else if(IccCard.INTENT_VALUE_LOCKED_NETWORK_SUBSET.equals(reason)) {
           Log.i(LOG_TAG,"SIM NETWORK SUBSET Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_SIM_NETWORK_SUBSET;
        } else if(IccCard.INTENT_VALUE_LOCKED_CORPORATE.equals(reason)) {
           Log.i(LOG_TAG,"SIM CORPORATE Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_SIM_CORPORATE;
        } else if(IccCard.INTENT_VALUE_LOCKED_SERVICE_PROVIDER.equals(reason)) {
           Log.i(LOG_TAG,"SIM SERVICE PROVIDER Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_SIM_SERVICE_PROVIDER;
        } else if(IccCard.INTENT_VALUE_LOCKED_SIM.equals(reason)) {
           Log.i(LOG_TAG,"SIM SIM Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_SIM_SIM;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_NETWORK1.equals(reason)) {
           Log.i(LOG_TAG,"RUIM NETWORK1 Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_NETWORK1;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_NETWORK2.equals(reason)) {
           Log.i(LOG_TAG,"RUIM NETWORK2 Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_NETWORK2;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_HRPD.equals(reason)) {
           Log.i(LOG_TAG,"RUIM HRPD Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_HRPD;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_CORPORATE.equals(reason)) {
           Log.i(LOG_TAG,"RUIM CORPORATE Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_CORPORATE;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_SERVICE_PROVIDER.equals(reason)) {
           Log.i(LOG_TAG,"RUIM SERVICE PROVIDER Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_SERVICE_PROVIDER;
        } else if(IccCard.INTENT_VALUE_LOCKED_RUIM_RUIM.equals(reason)) {
           Log.i(LOG_TAG,"RUIM RUIM Depersonalization");
           subtype = IccDepersonalizationConstants.ICC_RUIM_RUIM;
        } else {
           Log.e(LOG_TAG,"Unsupported Depersonalization: reason = " + reason);
        }

        if (subtype != IccDepersonalizationConstants.ICC_SIM_NETWORK) {
            IccDepersonalizationPanel dpPanel =
                 new IccDepersonalizationPanel(PhoneApp.getInstance(),subtype);
            dpPanel.show();
        }
    }
}
