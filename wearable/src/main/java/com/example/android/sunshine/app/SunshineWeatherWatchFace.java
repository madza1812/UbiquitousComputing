/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.example.android.sunshine.danga.wearable.watchface.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWeatherWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        public final String TAG = Engine.class.getSimpleName();

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient = false;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        private int specWidth;
        private int specHeight;
        /**
         * Watch Face Layout
         */
        private View watchfaceWeatherLayout;

        /**
         * Watch Face Layout components
         */
        private LinearLayout watchfaceContainer;
        private TextClock time;
        private TextView date, highTempTextView, lowTempTextView;
        private ImageView weatherDescIcon;

        private final Point displaySize = new Point();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mAntialias = false;
        GoogleApiClient mGoogleApiClient;

        private static final String WEARABLE_WEATHER_DATA_PATH = "/weather_data_path_wearable";
        private static final String WEARABLE_WEATHER_ID_KEY = "weather_id_key_wearable";
        private static final String WEARABLE_HIGH_TEMP_KEY = "high_temp_key_wearable";
        private static final String WEARABLE_LOW_TEMP_KEY = "low_temp_key_wearable";

        private static final int INVALID_WEATHER_ID = 2000;
        private static final double INVALID_TEMP = 1000;

        private int weatherId;
        private double highTemp, lowTemp;

        @Override
        public void onConnected(Bundle bundle) {
            Log.v(TAG, "onConnected: Successfully connected to Google API client");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            if (weatherId == INVALID_WEATHER_ID
                    || highTemp == INVALID_TEMP
                    || lowTemp == INVALID_TEMP ) {
                Log.v(TAG, "onConnected: loading Initial Weather Data");
                loadInitialWeatherData();
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.v(TAG, "onConnectionSuspended(): Connection to Google API client was suspended");
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: \" + result");
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            //Wearable.NodeApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v(TAG, "onDataChanged(): " + dataEventBuffer);
            for (DataEvent event :dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equalsIgnoreCase(WEARABLE_WEATHER_DATA_PATH)) {
                        updateWeatherDataFromDataItem(item);
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            //Resources resources = SunshineWeatherWatchFace.this.getResources();

            // Initialize the GoogleApiClient
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWeatherWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            mTime = new Time();

            // Inflate the watchface layout
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            watchfaceWeatherLayout = inflater.inflate(R.layout.watchface_weather_layout, null);

            // Load display spec
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            // Initialize view components of watchface layout
            watchfaceContainer = (LinearLayout) watchfaceWeatherLayout.findViewById(R.id.watchface_container);
            time = (TextClock) watchfaceWeatherLayout.findViewById(R.id.watch_time_textClock);
            date = (TextView) watchfaceWeatherLayout.findViewById(R.id.watch_date_textView);
            weatherDescIcon = (ImageView) watchfaceWeatherLayout.findViewById(R.id.watch_weather_desc_imageView);
            highTempTextView = (TextView) watchfaceWeatherLayout.findViewById(R.id.watch_high_temp_textView);
            lowTempTextView = (TextView) watchfaceWeatherLayout.findViewById(R.id.watch_low_temp_textView);
            weatherId = INVALID_WEATHER_ID;
            highTemp = INVALID_TEMP;
            lowTemp = INVALID_TEMP;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void loadInitialWeatherData() {
            Log.v(TAG, "on loadInitialWeatherData");
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(DataItemBuffer dataItems) {
                    Log.v(TAG, "on getDataItems count = " + dataItems.getCount());
                    DataItem dataItem;
                    for (int i = 0; i < dataItems.getCount(); i++) {
                        dataItem = dataItems.get(i);
                        Log.v(TAG, "data path = " + dataItem.getUri().getPath());
                        Log.v(TAG, "data uri = " + dataItem.getUri());
                        if (WEARABLE_WEATHER_DATA_PATH.equals(dataItem.getUri().getPath())) {
                            Log.v(TAG, "Data path matched");
                            updateWeatherDataFromDataItem(dataItem);
                            invalidate();
                        }
                    }
                }
            });
        }

        private void updateWeatherDataFromDataItem(DataItem item) {
            Log.v(TAG, "updating weather data");
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            weatherId = dataMap.getInt(WEARABLE_WEATHER_ID_KEY, INVALID_WEATHER_ID);
            highTemp = dataMap.getDouble(WEARABLE_HIGH_TEMP_KEY, INVALID_TEMP);
            lowTemp = dataMap.getDouble(WEARABLE_LOW_TEMP_KEY, INVALID_TEMP);
            Log.v(TAG, "weatherId = " + String.valueOf(weatherId)
                    + "highTemp = " + String.valueOf(highTemp)
                    + "lowTemp = " + String.valueOf(lowTemp));
        }


        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            if (isRound) {
                mXOffset = mYOffset = 0;
            } else {
                mXOffset = mYOffset = 0;
            }
            specWidth = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            specHeight = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);
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
            /*Log.v(TAG, "onAmbientChanged inAmbientMode = " + String.valueOf(inAmbientMode)
                        + " | mAmbient = " + String.valueOf(mAmbient));*/
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    //Log.v(TAG, "onAmbientModeChanged: LowBitAmbient is true");
                    mAntialias = !mAmbient;
                    time.getPaint().setAntiAlias(mAntialias);
                    highTempTextView.getPaint().setAntiAlias(mAntialias);
                    lowTempTextView.getPaint().setAntiAlias(mAntialias);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            //Log.v(TAG, "onDraw withm ambient = " + String.valueOf(mAmbient));

            mTime.setToNow();
            if (!mAmbient) {
                watchfaceContainer.setBackgroundColor(ContextCompat.getColor(
                        getApplicationContext(),
                        R.color.primary_bgr)
                );
                if (date.getVisibility() == View.GONE)
                    date.setVisibility(View.VISIBLE);
                date.setText(Util.formatDate(mTime.toMillis(true)));
                if (time.is24HourModeEnabled()) {
                    time.setFormat24Hour("HH:mm");
                    time.setTextColor(ContextCompat.getColor(
                            getApplicationContext(),
                            R.color.primary_text_color)
                    );
                    time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 55);
                } else {
                    time.setFormat12Hour("HH:mm a");
                }
                if (highTemp != INVALID_TEMP && lowTemp != INVALID_TEMP) {
                    highTempTextView.setText(Util.formatTemperature(highTemp));
                    highTempTextView.setTextColor(ContextCompat.getColor(
                            getApplicationContext(),
                            R.color.primary_text_color)
                    );
                    lowTempTextView.setText(Util.formatTemperature(lowTemp));
                    lowTempTextView.setTextColor(ContextCompat.getColor(
                            getApplicationContext(),
                            R.color.primary_sub_text_color)
                    );
                    if (weatherDescIcon.getVisibility() == View.GONE)
                        weatherDescIcon.setVisibility(View.VISIBLE);
                    weatherDescIcon.setImageResource(Util.getIconResourceForWeatherCondition(weatherId));
                }
            } else { // Ambient Mode
                watchfaceContainer.setBackgroundColor(ContextCompat.getColor(
                        getApplicationContext(),
                        R.color.ambient_bgr_color)
                );
                date.setVisibility(View.GONE);
                time.setTextColor(ContextCompat.getColor(
                        getApplicationContext(),
                        R.color.ambient_text_color)
                );
                if (time.is24HourModeEnabled()) {
                    time.setFormat24Hour("HH:mm");
                    time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 55);
                } else {
                    time.setFormat12Hour("HH:mm a");
                }
                if (highTemp != INVALID_TEMP && lowTemp != INVALID_TEMP) {
                    highTempTextView.setTextColor(ContextCompat.getColor(
                            getApplicationContext(),
                            R.color.ambient_text_color)
                    );
                    highTempTextView.setText(Util.formatTemperature(highTemp));
                    lowTempTextView.setTextColor(ContextCompat.getColor(
                            getApplicationContext(),
                            R.color.ambient_sub_text_color)
                    );
                    lowTempTextView.setText(Util.formatTemperature(lowTemp));
                    weatherDescIcon.setVisibility(View.GONE);
                }
            }
            watchfaceWeatherLayout.measure(specWidth, specHeight);
            watchfaceWeatherLayout.layout(
                    0,
                    0,
                    watchfaceWeatherLayout.getMeasuredWidth(),
                    watchfaceWeatherLayout.getMeasuredHeight()
            );
            canvas.drawColor(Color.BLACK);
            canvas.translate(mXOffset, mYOffset);
            watchfaceWeatherLayout.draw(canvas);
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
    }
}
