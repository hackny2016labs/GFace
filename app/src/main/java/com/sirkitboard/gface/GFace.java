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

package com.sirkitboard.gface;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.provider.WearableCalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class GFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        /* provide your watch face implementation */
        return new Engine();
    }

    /* implement service callback methods */
    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;
        static final int INTERACTIVE_UPDATE_RATE_MS = 1000;
        // Constant to help calculate clock hand rotations
        final float TWO_PI = (float) Math.PI * 2f;
        final float NOTCH_INCREMENT_RAD = (float) TWO_PI / 12;

        Calendar mCalendar;

        // device features
        boolean mLowBitAmbient;
        boolean mRegisteredTimeZoneReceiver;

        // graphic objects
        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundScaledBitmap;

        Paint mHourPointPaint;
        Paint mNotchPaint;

        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler
                                    .sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // configure the system UI (see next section)
            setWatchFaceStyle(new WatchFaceStyle.Builder(GFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle
                            .BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            // load the background image
            Resources resources = GFace.this.getResources();
            Drawable backgroundDrawable = resources.getDrawable(R.drawable.background_simple, null);
            mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();
            Log.e("com.sirkit.gface", mBackgroundBitmap.toString());

            mNotchPaint = new Paint();
            mNotchPaint.setARGB(255, 200, 200, 200);
            mNotchPaint.setStrokeWidth(2.0f);
            mNotchPaint.setAntiAlias(true);
            mNotchPaint.setStrokeCap(Paint.Cap.ROUND);

            mHourPointPaint = new Paint();
            mHourPointPaint.setARGB(255, 200, 200, 200);
            mHourPointPaint.setStrokeWidth(12.0f);
            mHourPointPaint.setAntiAlias(true);
            mHourPointPaint.setStrokeCap(Paint.Cap.ROUND);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
//            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
//                    false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mNotchPaint.setAntiAlias(true);
            }
            invalidate();
            updateTimer();

        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Update the time
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            int width = bounds.width();
            int height = bounds.height();

            canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, null);

            // Find the center. Ignore the window insets so that, on round watches
            // with a "chin", the watch face is centered on the entire screen, not
            // just the usable portion.
            float centerX = width / 2f;
            float centerY = height / 2f;

            // Compute rotations and lengths for the clock hands.
            float seconds = mCalendar.get(Calendar.SECOND) +
                    mCalendar.get(Calendar.MILLISECOND) / 1000f;
            float secRot = seconds / 60f * TWO_PI;
            float minutes = mCalendar.get(Calendar.MINUTE) + seconds / 60f;
            float minRot = minutes / 60f * TWO_PI;
            float hours = mCalendar.get(Calendar.HOUR) + minutes / 60f;
            float hrRot = hours / 12f * TWO_PI;

            float notchInnerRadius = centerX - 20;
//            float hrLength = centerX - 20;

            // Only draw the second hand in interactive mode.
            if (!isInAmbientMode()) {
                float notchRot = 0;
                float notchXStart, notchXEnd, notchYStart, notchYEnd;
                for(int notchCount = 0; notchCount < 12; notchCount++) {
                    notchXStart = (float) Math.sin(notchRot) * notchInnerRadius;
                    notchXEnd = (float) Math.sin(notchRot) * (centerX);

                    notchYStart = (float) -Math.cos(notchRot) * notchInnerRadius;
                    notchYEnd = (float) -Math.cos(notchRot) * (centerY);

                    canvas.drawLine(centerX + notchXStart, centerY + notchYStart, centerX + notchXEnd, centerY + notchYEnd, mNotchPaint);

//                    Log.d("Increment", String.format("Value : %f",  NOTCH_INCREMENT_RAD));
//                    Log.d("Notch Values", String.format("rot: %f xs:%f xe:%f ys:%f ye%f",  notchRot, notchXStart, notchXEnd, notchYStart, notchYEnd));

                    notchRot += NOTCH_INCREMENT_RAD;

                }
            }

            float timeX = (float) Math.sin(hrRot) * notchInnerRadius;
            float timeY = (float) -Math.cos(hrRot) * notchInnerRadius;

            canvas.drawPoint(centerX + timeX, centerY + timeY, mHourPointPaint );
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible and
            // whether we're in ambient mode, so we may need to start or stop the timer
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            GFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            GFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

//        private class LoadMeetingsTask extends AsyncTask<Void, Void, Integer> {
//            @Override
//            protected Integer doInBackground(Void... voids) {
//                long begin = System.currentTimeMillis();
//                Uri.Builder builder =
//                        WearableCalendarContract.Instances.CONTENT_URI.buildUpon();
//                ContentUris.appendId(builder, begin);
//                ContentUris.appendId(builder, begin + DateUtils.DAY_IN_MILLIS);
//                final Cursor cursor = getContentResolver() .query(builder.build(),
//                        null, null, null, null);
//                int numMeetings = cursor.getCount();
//                Log.d("", cursor.getColumnNames().toString());
////                if (Log.isLoggable(TAG, Log.VERBOSE)) {
////                    Log.v(TAG, "Num meetings: " + numMeetings);
////                }
//                return numMeetings;
//            }
//
//            @Override
//            protected void onPostExecute(Integer result) {
//        /* get the number of meetings and set the next timer tick */
//                onMeetingsLoaded(result);
//            }
//        }

    }
}
