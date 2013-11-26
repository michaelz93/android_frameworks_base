/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.keyguard;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;

import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Xfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";

    private KeyguardSecurityCallback mCallback;
    private GlowPadView mGlowPadView;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;
    private float mBatteryLevel;
    private String[] mStoredTargets;
    private int mTargetOffset;
    private boolean mIsScreenLarge;
    private int mCreationOrientation;

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(View v, int target) {
	    if (mStoredTargets == null) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);

            switch (resId) {
                case R.drawable.ic_action_assist_generic:
                    Intent assistIntent =
                            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
                    if (assistIntent != null) {
                        mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                    } else {
                        Log.w(TAG, "Failed to get intent for assist activity");
                    }
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_camera:
                    mActivityLauncher.launchCamera(null, null);
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_unlock_phantom:
                case R.drawable.ic_lockscreen_unlock:
                    mCallback.userActivity(0);
                    mCallback.dismiss(false);
                break;
            }
         } else {
                final boolean isLand = mCreationOrientation == Configuration.ORIENTATION_LANDSCAPE;
                if ((target == 0 && (mIsScreenLarge || !isLand)) || (target == 2 && !mIsScreenLarge && isLand)) {
                    mCallback.dismiss(false);
                } else {
                    target -= 1 + mTargetOffset;
                    if (target < mStoredTargets.length && mStoredTargets[target] != null) {
                        if (mStoredTargets[target].equals(GlowPadView.EMPTY_TARGET)) {
                            mCallback.dismiss(false);
                        } else {
                            if (mStoredTargets[target].equals(GlowPadView.TOGGLE_RINGER_TARGET)) {
                                AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                                if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
                                    final boolean vibe = (Settings.System.getInt(
                                        mContext.getContentResolver(),
                                        Settings.System.VIBRATE_IN_SILENT, 1) == 1);
                                    audioManager.setRingerMode(vibe
                                        ? AudioManager.RINGER_MODE_VIBRATE
                                        : AudioManager.RINGER_MODE_SILENT);
                                } else {
                                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                                }
                                updateResources();
                            } else {
                                try {
                                    Intent launchIntent = Intent.parseUri(mStoredTargets[target], 0);
                                    mActivityLauncher.launchActivity(launchIntent, false, true, null, null);
                                    return;
                                } catch (URISyntaxException e) {
                                }
                            }
                        }
                    }
                }
            }
        }

        public void onReleased(View v, int handle) {
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {

        }

        public void onTargetChange(View v, int target) {

        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }

        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus batStatus) {
            updateLockscreenBattery(batStatus);
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }	

        @Override
        Context getContext() {
            return mContext;
        }};

    public KeyguardSelectorView(Context context) {
        this(context, null);
	mCreationOrientation = Resources.getSystem().getConfiguration().orientation;
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        updateTargets();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();

	final int unsecureUnlockMethod = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCKSCREEN_UNSECURE_USED, 1);
        final int lockBeforeUnlock = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_BEFORE_UNLOCK, 0);

        //bring emergency button on slider lockscreen to front when lockBeforeUnlock is enabled
        //to make it clickable
        if (unsecureUnlockMethod == 0 && lockBeforeUnlock == 1) {
            LinearLayout ecaContainer = (LinearLayout) findViewById(R.id.keyguard_selector_fade_container);
            ecaContainer.bringToFront();
        }
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
    }

    public boolean isScreenLarge() {
        final int screenSize = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        boolean isScreenLarge = screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE ||
                screenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE;
        return isScreenLarge;
    }

    private StateListDrawable getLayeredDrawable(Drawable back, Drawable front, int inset, boolean frontBlank) {
        Resources res = getResources();
        InsetDrawable[] inactivelayer = new InsetDrawable[2];
        InsetDrawable[] activelayer = new InsetDrawable[2];
        inactivelayer[0] = new InsetDrawable(res.getDrawable(R.drawable.ic_lockscreen_lock_pressed), 0, 0, 0, 0);
        inactivelayer[1] = new InsetDrawable(front, inset, inset, inset, inset);
        activelayer[0] = new InsetDrawable(back, 0, 0, 0, 0);
        activelayer[1] = new InsetDrawable(frontBlank ? res.getDrawable(android.R.color.transparent) : front, inset, inset, inset, inset);
        StateListDrawable states = new StateListDrawable();
        LayerDrawable inactiveLayerDrawable = new LayerDrawable(inactivelayer);
        inactiveLayerDrawable.setId(0, 0);
        inactiveLayerDrawable.setId(1, 1);
        LayerDrawable activeLayerDrawable = new LayerDrawable(activelayer);
        activeLayerDrawable.setId(0, 0);
        activeLayerDrawable.setId(1, 1);
        states.addState(TargetDrawable.STATE_INACTIVE, inactiveLayerDrawable);
        states.addState(TargetDrawable.STATE_ACTIVE, activeLayerDrawable);
        states.addState(TargetDrawable.STATE_FOCUSED, activeLayerDrawable);
        return states;
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
    }

    private void updateTargets() {
        int currentUserHandle = mLockPatternUtils.getCurrentUser();
        DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUserHandle);
        boolean secureCameraDisabled = mLockPatternUtils.isSecure()
                && (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
        boolean cameraDisabledByAdmin = dpm.getCameraDisabled(null, currentUserHandle)
                || secureCameraDisabled;
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraTargetPresent =
            isTargetPresent(R.drawable.ic_lockscreen_camera);
        boolean searchTargetPresent =
            isTargetPresent(R.drawable.ic_action_assist_generic);

        if (cameraDisabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean currentUserSetup = 0 != Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0 /*default */,
                currentUserHandle);
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = cameraDisabledByAdmin || disabledBySimState || !cameraTargetPresent
                || !currentUserSetup;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent
                || !currentUserSetup;
        updateResources();
        updateLockscreenBattery(null);
    }

    public void updateResources() {
        String storedVal = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_TARGETS, UserHandle.USER_CURRENT);
        if (storedVal == null) {
            // Update the search icon with drawable from the search .apk
            if (!mSearchDisabled) {
                Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                        .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
                if (intent != null) {
                    // XXX Hack. We need to substitute the icon here but haven't formalized
                    // the public API. The "_google" metadata will be going away, so
                    // DON'T USE IT!
                    ComponentName component = intent.getComponent();
                    boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME + "_google", R.drawable.ic_action_assist_generic);

                    if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                                ASSIST_ICON_METADATA_NAME, R.drawable.ic_action_assist_generic)) {
                            Slog.w(TAG, "Couldn't grab icon from package " + component);
                    }
                }
            }

	// Enable magnetic targets
        mGlowPadView.setMagneticTargets(true);
        mGlowPadView.setEnableTarget(R.drawable.ic_lockscreen_camera, !mCameraDisabled);
        mGlowPadView.setEnableTarget(R.drawable.ic_action_assist_generic, !mSearchDisabled);
        } else {
            mStoredTargets = storedVal.split("\\|");
            mIsScreenLarge = isScreenLarge();
            ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();
            final Resources res = getResources();
            final int targetInset = res.getDimensionPixelSize(com.android.internal.R.dimen.lockscreen_target_inset);
            final PackageManager packMan = mContext.getPackageManager();
            final boolean isLandscape = mCreationOrientation == Configuration.ORIENTATION_LANDSCAPE;
            final Drawable blankActiveDrawable = res.getDrawable(R.drawable.ic_lockscreen_target_activated);
            final InsetDrawable activeBack = new InsetDrawable(blankActiveDrawable, 0, 0, 0, 0);
            //Magnetic target replacement
            final Drawable blankInActiveDrawable = res.getDrawable(R.drawable.ic_lockscreen_lock_pressed);
            final Drawable unlockActiveDrawable = res.getDrawable(R.drawable.ic_lockscreen_unlock_activated);
	// Disable magnetic target
            mGlowPadView.setMagneticTargets(false);
            // Shift targets for landscape lockscreen on phones
            mTargetOffset = isLandscape && !mIsScreenLarge ? 2 : 0;
            if (mTargetOffset == 2) {
                storedDraw.add(new TargetDrawable(res, null));
                storedDraw.add(new TargetDrawable(res, null));
            }
            // Add unlock target
            storedDraw.add(new TargetDrawable(res, res.getDrawable(R.drawable.ic_lockscreen_unlock)));
            for (int i = 0; i < 8 - mTargetOffset - 1; i++) {
                int tmpInset = targetInset;
                if (i < mStoredTargets.length) {
                    String uri = mStoredTargets[i];
                    if (!uri.equals(GlowPadView.EMPTY_TARGET)) {
                        try {
                            Drawable front = null;
                            Drawable back = activeBack;
                            boolean frontBlank = false;
                            if (uri.equals(GlowPadView.TOGGLE_RINGER_TARGET)) {
                                String iconResource = "ic_lockscreen";
                                AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                                if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
                                    iconResource = iconResource.concat("_silence");
                                } else {
                                    iconResource = iconResource.concat("_speaker");
                                }
                                front = res.getDrawable(res.getIdentifier(
                                    new StringBuilder().append(iconResource).append("_normal").toString(), "drawable", "android"));
                                back = res.getDrawable(res.getIdentifier(
                                    new StringBuilder().append(iconResource).append("_activated").toString(), "drawable", "android"));
                                tmpInset = 0;
                                frontBlank = true;
                                storedDraw.add(new TargetDrawable(res, getLayeredDrawable(back, front, tmpInset, frontBlank)));
                            } else {
                                Intent in = Intent.parseUri(uri,0);
                                if (in.hasExtra(GlowPadView.ICON_FILE)) {
                                    String fSource = in.getStringExtra(GlowPadView.ICON_FILE);
                                    if (fSource != null) {
                                        File fPath = new File(fSource);
                                        if (fPath.exists()) {
                                            front = new BitmapDrawable(res, getRoundedCornerBitmap(BitmapFactory.decodeFile(fSource)));
                                            tmpInset = tmpInset + 5;
                                        }
                                    }
                                } else if (in.hasExtra(GlowPadView.ICON_RESOURCE)) {
                                    String rSource = in.getStringExtra(GlowPadView.ICON_RESOURCE);
                                    String rPackage = in.getStringExtra(GlowPadView.ICON_PACKAGE);
                                    if (rSource != null) {
                                        if (rPackage != null) {
                                            try {
                                                Context rContext = mContext.createPackageContext(rPackage, 0);
                                                int id = rContext.getResources().getIdentifier(rSource, "drawable", rPackage);
                                                front = rContext.getResources().getDrawable(id);
                                                id = rContext.getResources().getIdentifier(rSource.replaceAll("_normal", "_activated"),
                                                        "drawable", rPackage);
                                                back = rContext.getResources().getDrawable(id);
                                                tmpInset = 0;
                                                frontBlank = true;
                                            } catch (NameNotFoundException e) {
                                                e.printStackTrace();
                                            } catch (NotFoundException e) {
                                                e.printStackTrace();
                                            }
                                        } else {
                                            front = res.getDrawable(res.getIdentifier(rSource, "drawable", "android"));
                                            back = res.getDrawable(res.getIdentifier(
                                                    rSource.replaceAll("_normal", "_activated"), "drawable", "android"));
                                            tmpInset = 0;
                                            frontBlank = true;
                                        }
                                    }
                                }
                                if (front == null || back == null) {
                                    ActivityInfo aInfo = in.resolveActivityInfo(packMan, PackageManager.GET_ACTIVITIES);
                                    if (aInfo != null) {
                                        front = aInfo.loadIcon(packMan);
                                    } else {
                                        front = res.getDrawable(android.R.drawable.sym_def_app_icon);
                                    }
                                }
                                TargetDrawable nDrawable = new TargetDrawable(res, getLayeredDrawable(back,front, tmpInset, frontBlank));
                                ComponentName compName = in.getComponent();
                                if (compName != null) {
                                    String cls = compName.getClassName();
                                    if (cls.equals("com.android.camera.CameraLauncher")) {
                                        nDrawable.setEnabled(!mCameraDisabled);
                                    } else if (cls.equals("SearchActivity")) {
                                        nDrawable.setEnabled(!mSearchDisabled);
                                    }
                                }
                                storedDraw.add(nDrawable);
                            }
                        } catch (Exception e) {
                            storedDraw.add(new TargetDrawable(res, 0));
                        }
                    } else {
                        storedDraw.add(new TargetDrawable(res, getLayeredDrawable(unlockActiveDrawable, blankInActiveDrawable, tmpInset, true)));
                    }
                } else {
                    storedDraw.add(new TargetDrawable(res, 0));
                }
            }
            mGlowPadView.setTargetResources(storedDraw);
        }
    }

    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
            bitmap.getHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int color = 0xff424242;
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);
        final float roundPx = 24;
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(color);
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint);
        paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mUpdateCallback);
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mUpdateCallback);
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    public void updateLockscreenBattery(KeyguardUpdateMonitor.BatteryStatus status) {
        if (Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.BATTERY_AROUND_LOCKSCREEN_RING,
                0 /*default */,
                UserHandle.USER_CURRENT) == 1) {
            if (status != null) mBatteryLevel = status.level;
            float cappedBattery = mBatteryLevel;

            if (mBatteryLevel < 15) {
                cappedBattery = 15;
            }
            else if (mBatteryLevel > 90) {
                cappedBattery = 90;
            }

            final float hue = (cappedBattery - 15) * 1.6f;
            mGlowPadView.setArc(mBatteryLevel * 3.6f, Color.HSVToColor(0x80, new float[]{ hue, 1.f, 1.f }));
        } else {
            mGlowPadView.setArc(0, 0);
        }
    }
}
