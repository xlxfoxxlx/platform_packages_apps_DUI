/**
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2016 The DirtyUnicorns Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.systemui.navigation.smartbar;

import com.android.systemui.navigation.smartbar.SmartBackButtonDrawable;

import android.animation.Animator;
import android.content.res.Resources;
import android.animation.ObjectAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.FloatProperty;
import android.util.Property;

public class SmartBackButtonDrawable extends Drawable {
    private final Drawable mWrappedDrawable;
    private float mRotation;
    private Animator mCurrentAnimator;
    private int mIcontint = SmartBarView.updatetint();
                

    private static final int ANIMATION_DURATION = 200;
    public static final Property<SmartBackButtonDrawable, Float> ROTATION
            = new FloatProperty<SmartBackButtonDrawable>("rotation") {
        @Override
        public void setValue(SmartBackButtonDrawable object, float value) {
            object.setRotation(value);
        }

        @Override
        public Float get(SmartBackButtonDrawable object) {
            return object.getRotation();
        }
    };

    public SmartBackButtonDrawable(Drawable wrappedDrawable) {
        mWrappedDrawable = wrappedDrawable;
    }   

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = mWrappedDrawable.getBounds();
        final int boundsCenterX = bounds.width() / 2;
        final int boundsCenterY = bounds.height() / 2;

        canvas.translate(boundsCenterX, boundsCenterY);
        canvas.rotate(mRotation);
        canvas.translate(- boundsCenterX, - boundsCenterY);
        if(mIcontint != -1) {
	mWrappedDrawable.setTint(mIcontint);
        } else {
        mWrappedDrawable.setTintList(null);
        }
        mWrappedDrawable.draw(canvas);
    }

    @Override
    public void setBounds(Rect bounds) {
        mWrappedDrawable.setBounds(bounds);
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        mWrappedDrawable.setBounds(left, top, right, bottom);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mWrappedDrawable.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        mWrappedDrawable.setAlpha(alpha);
        if (mCurrentAnimator != null) {
            mCurrentAnimator.end();
        }
    }

    @Override
    public int getAlpha() {
        return mWrappedDrawable.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return mWrappedDrawable.getOpacity();
    }

    @Override
    public int getIntrinsicWidth() {
        return mWrappedDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mWrappedDrawable.getIntrinsicHeight();
    }

    public void setRotation(float rotation) {
        mRotation = rotation;
        invalidateSelf();
    }

    public float getRotation() {
        return mRotation;
    }

    public void setImeVisible(boolean ime) {
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }

        final float nextRotation = ime ? - 90 : 0;
        if (mRotation == nextRotation) {
            return;
        }

        if (isVisible() && ActivityManager.isHighEndGfx()) {
            mCurrentAnimator = ObjectAnimator.ofFloat(this, ROTATION, nextRotation)
                    .setDuration(ANIMATION_DURATION);
            mCurrentAnimator.start();
        } else {
            setRotation(nextRotation);
        }
    }
}
