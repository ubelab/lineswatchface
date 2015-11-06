package com.marcouberti.lineswatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.marcouberti.lineswatchface.utils.ScreenUtils;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class LinesFace extends CanvasWatchFaceService {

    private static final String TAG = "NatureGradientsFace";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 1000;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final int BATTERY = 0;
    private static final int DAY_NUMBER = 1;
    private static final int DAY_WEEK = 2;
    private static final int MONTH = 3;
    private static final int YEAR = 4;
    private static final int NONE = 5;

    private int BOTTOM_COMPLICATION_MODE = BATTERY;
    private int LEFT_COMPLICATION_MODE = DAY_WEEK;
    private int RIGHT_COMPLICATION_MODE = DAY_NUMBER;

    int selectedColor;
    Paint mBackgroundPaint;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

        Paint mHandPaint;
        //Paint mDatePaint;
        Paint mSecondsCirclePaint,mComplicationPaint;
        Paint blackFillPaint, whiteFillPaint;
        //Paint circleStrokePaint,complicationsCircleStrokePaint;
        boolean mAmbient;
        Calendar mCalendar;
        Time mTime;
        boolean mIsRound =false;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(LinesFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mIsRound = insets.isRound();
            /*
            if(mIsRound) {
                mHandPaint.setTextSize(getResources().getDimension(R.dimen.font_size_time_round));
            }else{
                mHandPaint.setTextSize(getResources().getDimension(R.dimen.font_size_time_square));
            }
            */
        }

        @Override
        public void onTapCommand(@TapType int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case WatchFaceService.TAP_TYPE_TAP:
                    //detect screen area (CENTER_LEFT, CENTER_RIGHT, BOTTOM_CENTER)
                    handleTouch(x,y);
                    invalidate();
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH:
                    break;
                case WatchFaceService.TAP_TYPE_TOUCH_CANCEL:
                    break;

                default:
                    super.onTapCommand(tapType, x, y, eventTime);
                    break;
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(LinesFace.this)
                    .setAcceptsTapEvents(true)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false).
                            setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR)
                    .build());

            Resources resources = LinesFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setAntiAlias(true);
            mBackgroundPaint.setColor(getResources().getColor(R.color.dark_gray));

            mSecondsCirclePaint= new Paint();
            mSecondsCirclePaint.setAntiAlias(true);
            mSecondsCirclePaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mSecondsCirclePaint.setColor(Color.WHITE);
            mSecondsCirclePaint.setStrokeWidth(ScreenUtils.convertDpToPixels(getApplicationContext(), 1.5f));

            mComplicationPaint= new Paint();
            mComplicationPaint.setAntiAlias(true);
            mComplicationPaint.setColor(getResources().getColor(R.color.complications_gray));
            mComplicationPaint.setTypeface(Typeface.createFromAsset(getApplicationContext().getAssets(), "fonts/Dolce Vita.ttf"));
            mComplicationPaint.setTextSize(getResources().getDimension(R.dimen.font_size_string));

            mHandPaint= new Paint();
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mHandPaint.setColor(Color.RED);
            mHandPaint.setStrokeWidth(ScreenUtils.convertDpToPixels(getApplicationContext(), 1f));

            blackFillPaint = new Paint();
            blackFillPaint.setColor(Color.BLACK);
            blackFillPaint.setStyle(Paint.Style.FILL);
            blackFillPaint.setAntiAlias(true);

            whiteFillPaint = new Paint();
            whiteFillPaint.setColor(Color.WHITE);
            whiteFillPaint.setStyle(Paint.Style.FILL);
            whiteFillPaint.setAntiAlias(true);

            mTime = new Time();
            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    //mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            int width = bounds.width();
            int height = bounds.height();
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            // Draw the background.
            if (!mAmbient) {
                //BACKGROUND WITH GRADIENT
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
                canvas.drawCircle(width / 2, height / 2, (width/2)*0.65f, blackFillPaint);

            }else {//AMBIENT MODE
                //BLACK BG TO SAVE ENERGY
                canvas.drawColor(Color.BLACK);
            }

            //COMPLICATIONS
            if(!mAmbient) {
                //LEFT COMPLICATION
                String leftInfo = getLeftComplication();

                Rect batteryBounds = new Rect();
                mComplicationPaint.getTextBounds(leftInfo, 0, leftInfo.length(), batteryBounds);

                int dateLeft = (int) (width * (3f / 8f) - width * (1f / 16f) - (double) batteryBounds.width() / (double) 2);
                canvas.drawText(leftInfo, dateLeft, height / 2 + batteryBounds.height() / 2, mComplicationPaint);

                //RIGHT COMPLICATIONS
                String rightInfo = getRightComplication();

                Rect dayBounds = new Rect();
                mComplicationPaint.getTextBounds(rightInfo, 0, rightInfo.length(), dayBounds);

                int dayLeft = (int) (width * (5f / 8f) + width * (1f / 16f) - (double) dayBounds.width() / (double) 2);
                canvas.drawText(rightInfo, dayLeft, height / 2 + dayBounds.height() / 2, mComplicationPaint);

                //BOTTOM COMPLICATIONS
                String bottomInfo = getBottomComplication();

                Rect weekBounds = new Rect();
                mComplicationPaint.getTextBounds(bottomInfo, 0, bottomInfo.length(), weekBounds);

                int weekLeft = (int) (width / 2f - (double) weekBounds.width() / (double) 2);
                int weekTop = (int) (height * (5f / 8f) + height * (1f / 16f) + (double) weekBounds.height() / (double) 2);
                canvas.drawText(bottomInfo, weekLeft, weekTop, mComplicationPaint);
            }
            //END COMPLICATIONS


            int hor_w_half = ScreenUtils.convertDpToPixels(getApplicationContext(), 5);
            int lineW = ScreenUtils.convertDpToPixels(getApplicationContext(), 4);

            //Hours and minutes hands
            canvas.save();
            canvas.rotate(minutesRotation, width / 2, width / 2);
            canvas.drawLine(width / 2 - lineW, height / 2f * 4f / 5f, width / 2 - lineW, height / 25, mSecondsCirclePaint);
            canvas.drawLine(width / 2, height / 2f * 4f / 5f, width / 2, height / 25, mSecondsCirclePaint);
            canvas.drawLine(width / 2 + lineW, height / 2f * 4f / 5f, width / 2 + lineW, height / 25, mSecondsCirclePaint);
            canvas.restore();

            canvas.save();
            canvas.rotate(hoursRotation, width / 2, width / 2);

            int NUM = 18;
            int START = (int)(height / 2f * 4f / 5f);
            int END = (int)((height / 25f)*3f);
            int GAP = (END-START)/18;
            for(int i=0; i<NUM; i++) {
                canvas.drawLine(width / 2 -hor_w_half, START+GAP*i, width / 2 +hor_w_half, START+GAP*i, mSecondsCirclePaint);
            }

            //canvas.drawRoundRect(width / 2 - RRradius, (height / 25)*3, width / 2 + RRradius, height / 2f * 4f / 5f, RR, RR, mSecondsCirclePaint);
            canvas.restore();

            //seconds
            if(!mAmbient) {
                canvas.save();
                canvas.rotate(secondsRotation, width / 2, width / 2);
                mHandPaint.setColor(GradientsUtils.getGradients(getApplicationContext(),selectedColor));
                canvas.drawLine(width / 2, height / 2 + (height / 15) * 2f, width / 2, (height / 25) , mHandPaint);
                canvas.restore();
            }

            if(!mAmbient) {
                canvas.drawCircle(width / 2, height / 2, ScreenUtils.convertDpToPixels(getApplicationContext(), 4), mHandPaint);
            }else {
                canvas.drawCircle(width / 2, height / 2, ScreenUtils.convertDpToPixels(getApplicationContext(), 4), whiteFillPaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            LinesFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            LinesFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        private void updateConfigDataItemAndUiOnStartup() {
            NatureGradientsWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new NatureGradientsWatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            NatureGradientsWatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            addIntKeyIfMissing(config, NatureGradientsWatchFaceUtil.KEY_BACKGROUND_COLOR,
                    NatureGradientsWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
        }

        private void addIntKeyIfMissing(DataMap config, String key, int color) {
            if (!config.containsKey(key)) {
                config.putInt(key, color);
            }
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        NatureGradientsWatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }
                int color = config.getInt(configKey);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Found watch face config key: " + configKey + " -> "
                            + color);
                }
                if (updateUiForKey(configKey, color)) {
                    uiUpdated = true;
                }
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        /**
         * Updates the color of a UI item according to the given {@code configKey}. Does nothing if
         * {@code configKey} isn't recognized.
         *
         * @return whether UI has been updated
         */
        private boolean updateUiForKey(String configKey, int color) {
            if (configKey.equals(NatureGradientsWatchFaceUtil.KEY_BACKGROUND_COLOR)) {
                setGradient(color);
            } else {
                Log.w(TAG, "Ignoring unknown config key: " + configKey);
                return false;
            }
            return true;
        }

        private void setGradient(int color) {
            Log.d("color=",color+"");
            selectedColor = color;
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }
    }

    private void handleTouch(int x, int y) {
        int W = ScreenUtils.getScreenWidth(getApplicationContext());
        int H = ScreenUtils.getScreenHeight(getApplicationContext());
        int DELTA_X =W/5;
        int DELTA_Y = H/5;
        //LEFT CENTER
        if(x <(W/4 + DELTA_X) && x >(W/4 - DELTA_X)) {
            if(y <(H/2 + DELTA_Y) && y >(H/2 - DELTA_Y)) {
               handleTouchLeftCenter();
                return;
            }
        }
        //RIGHT CENTER
        if(x <(W*3/4 + DELTA_X) && x >(W*3/4 - DELTA_X)) {
            if(y <(H/2 + DELTA_Y) && y >(H/2 - DELTA_Y)) {
                handleTouchRightCenter();
                return;
            }
        }
        //BOTTOM CENTER
        if(x <(W/2 + DELTA_X) && x >(W/2 - DELTA_X)) {
            if(y <(H*3/4 + DELTA_Y) && y >(H*3/4 - DELTA_Y)) {
                handleTouchBottomCenter();
                return;
            }
        }
    }

    private void handleTouchBottomCenter() {
        if(BOTTOM_COMPLICATION_MODE == BATTERY) BOTTOM_COMPLICATION_MODE =DAY_NUMBER;
        else if(BOTTOM_COMPLICATION_MODE == DAY_NUMBER) BOTTOM_COMPLICATION_MODE =DAY_WEEK;
        else if(BOTTOM_COMPLICATION_MODE == DAY_WEEK) BOTTOM_COMPLICATION_MODE =MONTH;
        else if(BOTTOM_COMPLICATION_MODE == MONTH) BOTTOM_COMPLICATION_MODE =YEAR;
        else if(BOTTOM_COMPLICATION_MODE == YEAR) BOTTOM_COMPLICATION_MODE =NONE;
        else BOTTOM_COMPLICATION_MODE =BATTERY;
    }

    private void handleTouchRightCenter() {
        if(RIGHT_COMPLICATION_MODE == BATTERY) RIGHT_COMPLICATION_MODE =DAY_NUMBER;
        else if(RIGHT_COMPLICATION_MODE == DAY_NUMBER) RIGHT_COMPLICATION_MODE =DAY_WEEK;
        else if(RIGHT_COMPLICATION_MODE == DAY_WEEK) RIGHT_COMPLICATION_MODE =MONTH;
        else if(RIGHT_COMPLICATION_MODE == MONTH) RIGHT_COMPLICATION_MODE =YEAR;
        else if(RIGHT_COMPLICATION_MODE == YEAR) RIGHT_COMPLICATION_MODE =NONE;
        else RIGHT_COMPLICATION_MODE =BATTERY;
    }

    private void handleTouchLeftCenter() {
        if(LEFT_COMPLICATION_MODE == BATTERY) LEFT_COMPLICATION_MODE =DAY_NUMBER;
        else if(LEFT_COMPLICATION_MODE == DAY_NUMBER) LEFT_COMPLICATION_MODE =DAY_WEEK;
        else if(LEFT_COMPLICATION_MODE == DAY_WEEK) LEFT_COMPLICATION_MODE =MONTH;
        else if(LEFT_COMPLICATION_MODE == MONTH) LEFT_COMPLICATION_MODE =YEAR;
        else if(LEFT_COMPLICATION_MODE == YEAR) LEFT_COMPLICATION_MODE =NONE;
        else LEFT_COMPLICATION_MODE =BATTERY;
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<LinesFace.Engine> mWeakReference;

        public EngineHandler(LinesFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            LinesFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private String getLeftComplication() {
        if(LEFT_COMPLICATION_MODE == BATTERY) return getBatteryLevel()+"%";
        else if(LEFT_COMPLICATION_MODE == DAY_WEEK) return getWeekDay();
        else if(LEFT_COMPLICATION_MODE == DAY_NUMBER) return getDayNumber();
        else if(LEFT_COMPLICATION_MODE == MONTH) return getMonth();
        else if(LEFT_COMPLICATION_MODE == YEAR) return getYear();
        else return "";
    }

    private String getRightComplication() {
        if(RIGHT_COMPLICATION_MODE == BATTERY) return getBatteryLevel()+"%";
        else if(RIGHT_COMPLICATION_MODE == DAY_WEEK) return getWeekDay();
        else if(RIGHT_COMPLICATION_MODE == DAY_NUMBER) return getDayNumber();
        else if(RIGHT_COMPLICATION_MODE == MONTH) return getMonth();
        else if(RIGHT_COMPLICATION_MODE == YEAR) return getYear();
        else return "";
    }

    private String getBottomComplication() {
        if(BOTTOM_COMPLICATION_MODE == BATTERY) return getBatteryLevel()+"%";
        else if(BOTTOM_COMPLICATION_MODE == DAY_WEEK) return getWeekDay();
        else if(BOTTOM_COMPLICATION_MODE == DAY_NUMBER) return getDayNumber();
        else if(BOTTOM_COMPLICATION_MODE == MONTH) return getMonth();
        else if(BOTTOM_COMPLICATION_MODE == YEAR) return getYear();
        else return "";
    }

    private String getYear() {
        mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_number));
        return new SimpleDateFormat("yyyy").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getMonth() {
        mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_string));
        return new SimpleDateFormat("MMM").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getDayNumber() {
        mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_number));
        return new SimpleDateFormat("d").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getWeekDay() {
        mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_string));
        return new SimpleDateFormat("EEE").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    public int getBatteryLevel() {
        mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_number));
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        // Error checking that probably isn't needed but I added just in case.
        if(level == -1 || scale == -1) {
            return 50;
        }

        return (int)(((float)level / (float)scale) * 100.0f);
    }
}
