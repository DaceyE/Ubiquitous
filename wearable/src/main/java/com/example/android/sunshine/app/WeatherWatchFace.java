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
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {
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
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        byte weatherDateState;
        boolean mToggle;
        int mLastDate;

        final byte CURRENT_DAY_STATE = 0;
        final byte TOMORROW_STATE = 1;
        final byte OUT_OF_DATE_STATE = 2;

        String mHigh = "NA\u00b0";
        String mLow = "NA\u00b0";
        static final String defaultDegrees = "NA\u00b0";

        Bitmap mWeatherBitmap;
        int weatherId = Integer.MIN_VALUE;

        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mDateTextPaint;
        Paint mHighTextPaint;
        Paint mLowTextPaint;
        Paint mGrayBitmapPaint;

        /**
         *  Tells the createTextPaint(int,int) method to return the mTimeTextPaint
         */
        final int PAINT_TIME = 0;

        /**
         *  Tells the createTextPaint(int,int) method to return the mDateTextPaint
         */
        final int PAINT_DATE = 1;

        /**
         *  Tells the createTextPaint(int,int) method to return the mHighTextPaint
         */
        final int PAINT_HIGH = 2;

        /**
         *  Tells the createTextPaint(int,int) method to return the mLowTextPaint
         */
        final int PAINT_LOW = 3;


        float mXOffset;
        float mYOffset;
        float mYTimeOffset;
        float mYDateOffset;
        float mYLineOffset;
        float mYWeatherOffset;
        float mXWeatherOffset;
        float mWeatherIconSize;

        Resources resources;
        SharedPreferences weatherPreferences;

        boolean mAmbient;
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        Time mTime;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            resources = WeatherWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            int textColor1 = resources.getColor(R.color.digital_text);
            int textColor2 = resources.getColor(R.color.digital_text2);

            mTimeTextPaint = createTextPaint(textColor1, PAINT_TIME);
            mDateTextPaint = createTextPaint(textColor2, PAINT_DATE);
            mHighTextPaint = createTextPaint(textColor1, PAINT_HIGH);
            mLowTextPaint = createTextPaint(textColor2, PAINT_LOW);
            mGrayBitmapPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            mGrayBitmapPaint.setColorFilter(filter);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mYTimeOffset = resources.getFraction(R.fraction.digital_y_time_offset, 1, 1);
            mYDateOffset = resources.getFraction(R.fraction.digital_y_date_offset, 1, 1);
            mYLineOffset = resources.getFraction(R.fraction.digital_y_line_offset, 1, 1);
            mYWeatherOffset = resources.getFraction(R.fraction.digital_y_weather_offset, 1, 1);

            weatherPreferences = getApplicationContext().getSharedPreferences(
                    resources.getString(R.string.shared_preferences_weather_key),
                    Context.MODE_PRIVATE);

            mTime = new Time();
            mTime.setToNow();
            int today = mTime.getJulianDay(System.currentTimeMillis(),mTime.gmtoff);
            int date1 = weatherPreferences.getInt(WeatherListenerService.DATE1_KEY, Integer.MIN_VALUE);

            if (today == date1) {
                weatherDateState = CURRENT_DAY_STATE;
                mLastDate = date1;
                loadWeatherData();
            } else {
                int date2 = weatherPreferences.getInt(WeatherListenerService.DATE2_KEY, Integer.MIN_VALUE);
                if (today == date2) {
                    weatherDateState = TOMORROW_STATE;
                    mLastDate = date2;
                    loadWeatherData();
                } else {
                    weatherDateState = OUT_OF_DATE_STATE;
                    mLastDate = today;
                    loadWeatherData();
                }
            }

            mWeatherBitmap = BitmapFactory.decodeResource(resources, getIconResourceForWeatherCondition(weatherId));

            mToggle = WeatherListenerService.getToggle();
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

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
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
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            boolean isRound = insets.isRound();

            float weatherTextSize;

            if (isRound) {
                mXOffset = resources.getDimension(R.dimen.digital_x_offset_round);
                mTimeTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_round));
                mDateTextPaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size_round));
                weatherTextSize = resources.getDimension(R.dimen.digital_weather_text_size_round);

                mHighTextPaint.setTextSize(weatherTextSize);
                mLowTextPaint.setTextSize(weatherTextSize);

                mXWeatherOffset = resources.getFraction(R.fraction.digital_x_weather_offset_round, 1, 1);
                mWeatherIconSize = resources.getDimension(R.dimen.digital_weather_icon_size_round);
            } else {
                mXOffset = resources.getDimension(R.dimen.digital_x_offset);
                mTimeTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size));
                mDateTextPaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));

                weatherTextSize = resources.getDimension(R.dimen.digital_weather_text_size);
                mHighTextPaint.setTextSize(weatherTextSize);
                mLowTextPaint.setTextSize(weatherTextSize);

                mXWeatherOffset = resources.getFraction(R.fraction.digital_x_weather_offset, 1, 1);
                mWeatherIconSize = resources.getDimension(R.dimen.digital_weather_icon_size);
            }
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
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
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                    mHighTextPaint.setAntiAlias(!inAmbientMode);
                    mLowTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            final int height = bounds.height();
            final int width = bounds.width();
            final int centerX = bounds.centerX();

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            canvas.drawText(text, (width- mTimeTextPaint.measureText(text))/2,
                    height*mYTimeOffset, mTimeTextPaint);

            String dateText = weekDayFormat(mTime.weekDay)
                      + ", " + monthFormat(mTime.month)
                      + " " + mTime.monthDay
                      + " " + mTime.year;
            canvas.drawText(dateText, (width - mDateTextPaint.measureText(dateText)) / 2,
                    height * mYDateOffset, mDateTextPaint);

            float yLineOffset = height*mYLineOffset;
            canvas.drawLine(centerX-35,yLineOffset,
                    centerX+35,yLineOffset, mDateTextPaint);


            //Move state matchine to method
            if ( mTime.getJulianDay(System.currentTimeMillis(),mTime.gmtoff) != mLastDate || (mToggle != WeatherListenerService.getToggle())) { //bug 1,
                switch (weatherDateState) {
                    case CURRENT_DAY_STATE:
                        if (mToggle == WeatherListenerService.getToggle()) {
                            weatherDateState = TOMORROW_STATE; //Move up a state
                            loadWeatherData();
                        } else {
                            //Preserve state, but get the new data
                            mToggle = WeatherListenerService.getToggle();
                            loadWeatherData();
                        }
                        break;
                    case TOMORROW_STATE:
                        if (mToggle == WeatherListenerService.getToggle()) {
                            weatherDateState = OUT_OF_DATE_STATE; //Move up a state
                            loadWeatherData();
                        } else {
                            weatherDateState = CURRENT_DAY_STATE;  //Reset to initial state
                            mToggle = WeatherListenerService.getToggle();
                            loadWeatherData();
                        }
                        break;
                    case OUT_OF_DATE_STATE:
                        if (mToggle == WeatherListenerService.getToggle()) { //bug 2
                            // Preserve state and data.  There is no new data
                        } else {
                            weatherDateState = CURRENT_DAY_STATE; //Reset to initial state
                            mToggle = WeatherListenerService.getToggle();
                            loadWeatherData();
                        }
                        break;
                }
                mLastDate =  mTime.getJulianDay(System.currentTimeMillis(),mTime.gmtoff); //Update the last day.
            }

            float yWeatherOffset = height*mYWeatherOffset;
            float xHighOffset = ( width- mHighTextPaint.measureText(mHigh) )/2;

            canvas.drawText(mHigh, xHighOffset,yWeatherOffset, mHighTextPaint);


            canvas.drawText(mLow,
                    mXWeatherOffset*(width - ((xHighOffset + mLowTextPaint.measureText(mLow))/2)),
                    yWeatherOffset, mLowTextPaint);

            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                //Do nothing, so don't draw the weather bitmap
            } else {
                //RectF(left,top,right,bottom)
                float weatherIconSizeHalf = mWeatherIconSize/2;
                float weatherIconXCenter = (width/4)/mXWeatherOffset;

                if (mAmbient) {
                    if ( !(mBurnInProtection || mLowBitAmbient) ) {
                        canvas.drawBitmap(mWeatherBitmap,
                                null,
                                new RectF(weatherIconXCenter - weatherIconSizeHalf,
                                        yWeatherOffset - mWeatherIconSize,
                                        weatherIconXCenter + weatherIconSizeHalf,
                                        yWeatherOffset),
                                mGrayBitmapPaint);
                    }  // else don't draw the bitmap
                } else {
                    canvas.drawBitmap(mWeatherBitmap,
                            null,
                            new RectF(weatherIconXCenter - weatherIconSizeHalf,
                                    yWeatherOffset - mWeatherIconSize,
                                    weatherIconXCenter + weatherIconSizeHalf,
                                    yWeatherOffset),
                            null);
                }
            }
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

        /**
         * Formats the month from Time.weekDay
         * @param dayOfWeek An int 0-6, 0 being Sunday.
         * @return A three letter string representation of day
         */
        @Nullable
        private String weekDayFormat(int dayOfWeek){
            switch (dayOfWeek) {
                case 0:
                    return "SUN";
                case 1:
                    return "MON";
                case 2:
                    return "TUE";
                case 3:
                    return "WED";
                case 4:
                    return "THU";
                case 5:
                    return "FRI";
                case 6:
                    return "SAT";
                default:
                    return null;
            }
        }

        /**
         * Formats the month from Time.month
         * @param month The current month 0-11, starting at january
         * @return A 3 letter String representation of month
         */
        @Nullable
        private String monthFormat(int month){
            switch (month) {
                case 0:
                    return "JAN";
                case 1:
                    return "FEB";
                case 2:
                    return "MAR";
                case 3:
                    return "APR";
                case 4:
                    return "MAY";
                case 5:
                    return "JUN";
                case 6:
                    return "JUL";
                case 7:
                    return "AUG";
                case 8:
                    return "SEP";
                case 9:
                    return "OCT";
                case 10:
                    return "NOV";
                case 11:
                    return "DEC";
                default:
                    return null;
            }
        }

        /**
         * Helper method to provide the icon resource id according to the weather condition id returned
         * by the OpenWeatherMap call.
         * @param weatherId from OpenWeatherMap API response
         * @return resource id for the corresponding icon. -1 if no relation is found.
         */
        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            } else  {
                return R.drawable.question_mark;
            }
        }

        /**
         *
         * @param textColor
         * @param paintId
         * @return
         */
        private Paint createTextPaint(int textColor,int paintId) {
            Paint paint = new Paint();

            //Currently all the text paints are handled the same way.
//            switch (paintId) {
//                case PAINT_TIME:
//                    break;
//                case PAINT_DATE:
//                    break;
//                case PAINT_HIGH:
//                    break;
//                case PAINT_LOW:
//                    break;
//            }
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        void loadWeatherData() {
            int previousWeatherId = weatherId;

            if (weatherDateState == CURRENT_DAY_STATE) {
                mHigh = weatherPreferences.getString(WeatherListenerService.MAX1_KEY, defaultDegrees);
                mLow = weatherPreferences.getString(WeatherListenerService.MIN1_KEY, defaultDegrees);
                weatherId = weatherPreferences.getInt(WeatherListenerService.WEATHER_ID1_KEY, Integer.MIN_VALUE);

                if(weatherId != previousWeatherId) {
                    mWeatherBitmap = BitmapFactory.decodeResource(resources, getIconResourceForWeatherCondition(weatherId));
                }
            } else if (weatherDateState == TOMORROW_STATE) {
                mHigh = weatherPreferences.getString(WeatherListenerService.MAX2_KEY, defaultDegrees);
                mLow = weatherPreferences.getString(WeatherListenerService.MIN2_KEY, defaultDegrees);
                weatherId = weatherPreferences.getInt(WeatherListenerService.WEATHER_ID2_KEY, Integer.MIN_VALUE);

                if(weatherId != previousWeatherId) {
                    mWeatherBitmap = BitmapFactory.decodeResource(resources, getIconResourceForWeatherCondition(weatherId));
                }
            } else { // weatherDateSate == OUT_OF_DATE_STATE
                mHigh = defaultDegrees;
                mLow = defaultDegrees;
                weatherId = Integer.MIN_VALUE;

                if(weatherId != previousWeatherId) {
                    mWeatherBitmap = BitmapFactory.decodeResource(resources, getIconResourceForWeatherCondition(weatherId));
                }
            }
        }

    }
}
