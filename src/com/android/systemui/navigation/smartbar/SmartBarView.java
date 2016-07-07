/**
 * Copyright (C) 2016 The DirtyUnicorns Project
 * Copyright (C) 2014 SlimRoms
 * 
 * @author: Randall Rushing <randall.rushing@gmail.com>
 *
 * Much love and respect to SlimRoms for writing and inspiring
 * some of the dynamic layout methods
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
 * 
 * A new software key based navigation implementation that just vaporizes
 * AOSP and quite frankly everything currently on the custom firmware scene
 *
 */

package com.android.systemui.navigation.smartbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;

import com.android.internal.utils.du.ActionConstants;
import com.android.internal.utils.du.ActionHandler;
import com.android.internal.utils.du.DUActionUtils;
import com.android.internal.utils.du.Config;
import com.android.internal.utils.du.Config.ActionConfig;
import com.android.internal.utils.du.Config.ButtonConfig;
import com.android.systemui.navigation.BaseEditor;
import com.android.systemui.navigation.BaseNavigationBar;
import com.android.systemui.navigation.Res;
import com.android.systemui.navigation.NavigationController.NavbarOverlayResources;
import com.android.systemui.navigation.smartbar.SmartBackButtonDrawable;
import com.android.systemui.navigation.smartbar.SmartBarEditor;
import com.android.systemui.navigation.smartbar.SmartBarHelper;
import com.android.systemui.navigation.smartbar.SmartBarTransitions;
import com.android.systemui.navigation.smartbar.SmartBarView;
import com.android.systemui.navigation.smartbar.SmartButtonView;
import com.android.systemui.navigation.utils.SmartObserver.SmartObservable;
import com.android.systemui.statusbar.BarTransitions;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SmartBarView extends BaseNavigationBar {
    final static boolean DEBUG = false;
    final static String TAG = SmartBarView.class.getSimpleName();
    final static float PULSE_ALPHA_FADE = 0.3f; // take bar alpha low so keys are vaguely visible
                                                // but not intrusive during Pulse
    final static int PULSE_FADE_OUT_DURATION = 250;
    final static int PULSE_FADE_IN_DURATION = 200;

    static final int IME_HINT_MODE_HIDDEN = 0;
    static final int IME_HINT_MODE_ARROWS = 1;
    static final int IME_HINT_MODE_PICKER = 2;

    private static Set<Uri> sUris = new HashSet<Uri>();    
    static {
        sUris.add(Settings.Secure.getUriFor("smartbar_context_menu_mode"));
        sUris.add(Settings.Secure.getUriFor("smartbar_ime_hint_mode"));
        sUris.add(Settings.Secure.getUriFor("smartbar_button_animation_style"));
    }

    private SmartObservable mObservable = new SmartObservable() {
        @Override
        public Set<Uri> onGetUris() {
            return sUris;
        }

        @Override
        public void onChange(Uri uri) {
            if (uri.equals(Settings.Secure.getUriFor("smartbar_context_menu_mode"))) {
                updateContextLayoutSettings();
            } else if (uri.equals(Settings.Secure.getUriFor("smartbar_ime_hint_mode"))) {
                updateImeHintModeSettings();
                refreshImeHintMode();
            } else if (uri.equals(Settings.Secure.getUriFor("smartbar_button_animation_style"))) {
                updateAnimationStyle();
            }
        }
    };

    boolean mShowMenu;
    int mNavigationIconHints = 0;

    private final SmartBarTransitions mBarTransitions;
    private SmartBarEditor mEditor;

    // hold a reference to primary buttons in order of appearance on screen
    private ArrayList<String> mCurrentSequence = new ArrayList<String>();
    private View mContextRight, mContextLeft, mCurrentContext;
    private boolean mHasLeftContext;
    private int mImeHintMode;
    private int mButtonAnimationStyle;
    private static boolean mNavTintSwitch;
    public static int mIcontint;

    public SmartBarView(Context context) {
        super(context);
        mBarTransitions = new SmartBarTransitions(this);
        mEditor = new SmartBarEditor(this);
        mSmartObserver.addListener(mObservable);
        createBaseViews();
    }

    ArrayList<String> getCurrentSequence() {
        return mCurrentSequence;
    }

    @Override
    public void setResourceMap(NavbarOverlayResources resourceMap) {
        super.setResourceMap(resourceMap);
        recreateLayouts();
        updateImeHintModeSettings();
        updateContextLayoutSettings();
    }

    @Override
    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    @Override
    public void screenPinningStateChanged(boolean enabled) {
        super.screenPinningStateChanged(enabled);
        mEditor.screenPinningStateChanged(enabled);
        setScreenPinningVisibility();
    }

    @Override
    protected void onInflateFromUser() {
        mEditor.notifyScreenOn(mScreenOn);
    }

    @Override
    public void setListeners(OnTouchListener userAutoHideListener) {
        super.setListeners(userAutoHideListener);
        setOnTouchListener(mUserAutoHideListener);
    }

    @Override
    public void onRecreateStatusbar() {
        mEditor.updateResources(null);
        updateCurrentIcons();
    }

    @Override
    public void updateNavbarThemedResources(Resources res){
        super.updateNavbarThemedResources(res);
        for (int i = 0; i < mRotatedViews.length; i++) {
            ViewGroup container = (ViewGroup) mRotatedViews[i];
            ViewGroup lightsOut = (ViewGroup) container.findViewWithTag(Res.Common.LIGHTS_OUT);
            if (lightsOut != null) {
                final int nChildren = lightsOut.getChildCount();
                for (int j = 0; j < nChildren; j++) {
                    final View child = lightsOut.getChildAt(j);
                    if (child instanceof ImageView) {
                        final ImageView iv = (ImageView) child;
                        // clear out the existing drawable, this is required since the
                        // ImageView keeps track of the resource ID and if it is the same
                        // it will not update the drawable.
                        iv.setImageDrawable(null);
                        iv.setImageDrawable(mResourceMap.mLightsOutLarge);
                    }
                }
            }
        }
        updateCurrentIcons();
    }

    public void updateCurrentIcons() {
        for (SmartButtonView button : DUActionUtils.getAllChildren(this, SmartButtonView.class)) {
            setButtonDrawable(button);
        }
    }

    public void setButtonDrawable(SmartButtonView button) {
        ButtonConfig config = button.getButtonConfig();
        mNavTintSwitch = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.NAVBAR_TINT_SWITCH, 0) == 1;
	mIcontint = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NAVBAR_BUTTON_COLOR, 0xFFFFFFFF);
        Drawable d = null;
        if (config != null) {
            // a system navigation action icon is showing, get it locally
            if (!config.hasCustomIcon()
                    && config.isSystemAction()) {
                    d = mResourceMap.getActionDrawable(config.getActionConfig(ActionConfig.PRIMARY).getAction());
            } else {
                // custom icon or intent icon, get from library
                d = config.getCurrentIcon(getContext());
            }
            if (TextUtils.equals(config.getTag(), Res.Softkey.BUTTON_BACK)) {
                SmartBackButtonDrawable backDrawable = new SmartBackButtonDrawable(d);
                button.setImageDrawable(null);
                button.setImageDrawable(backDrawable);
                final boolean backAlt = (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
                backDrawable.setImeVisible(backAlt);
            } else {
                button.setImageDrawable(null);
                button.setImageDrawable(d);
            }
            if (mNavTintSwitch) {
            button.setColorFilter(mIcontint, Mode.SRC_IN);
            } else {
            button.setColorFilter(null);
            }
        }
    }
    
    public static int updatetint() {
    if (mNavTintSwitch) {
	return mIcontint; 
    } else {
	mIcontint = -1 ;
	return mIcontint; 
	 }
    } 

    @Override
    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public SmartButtonView getBackButton() {
        return (SmartButtonView) mCurrentView.findViewWithTag(Res.Softkey.BUTTON_BACK);
    }

    public SmartButtonView getHomeButton() {
        return (SmartButtonView) mCurrentView.findViewWithTag(Res.Softkey.BUTTON_HOME);
    }

    public SmartButtonView getMenuButton() {
        return (SmartButtonView) mCurrentContext.findViewWithTag(Res.Softkey.MENU_BUTTON);
    }

    SmartButtonView getImeSwitchButton() {
        return (SmartButtonView) mCurrentContext.findViewWithTag(Res.Softkey.IME_SWITCHER);
    }

    SmartButtonView findCurrentButton(String tag) {
        return (SmartButtonView) mCurrentView.findViewWithTag(tag);
    }

    SmartButtonView getScreenPinningButton() {
        // screenpinning button always in opposing context view
        ViewGroup group = (ViewGroup) (mHasLeftContext ? mContextRight : mContextLeft);
        return (SmartButtonView) group.findViewWithTag(Res.Softkey.STOP_SCREENPINNING);
    }

    SmartBackButtonDrawable getBackButtonIcon() {
        return (SmartBackButtonDrawable) getBackButton().getDrawable();
    }

    private ViewGroup getHiddenContext() {
        return (ViewGroup) (mCurrentContext == mContextRight ? mContextLeft : mContextRight);
    }

    private void setImeArrowsVisibility(View currentOrHidden, int visibility) {
        ViewGroup contextLeft = (ViewGroup)currentOrHidden.findViewWithTag(Res.Softkey.CONTEXT_VIEW_LEFT);
        contextLeft.findViewWithTag(Res.Softkey.IME_ARROW_LEFT).setVisibility(visibility);
        ViewGroup contextRight = (ViewGroup)currentOrHidden.findViewWithTag(Res.Softkey.CONTEXT_VIEW_RIGHT);
        contextRight.findViewWithTag(Res.Softkey.IME_ARROW_RIGHT).setVisibility(visibility);
    }

    @Override
    protected boolean areAnyHintsActive() {
        return super.areAnyHintsActive() || mShowMenu;
    }

    @Override
    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints)
            return;
        mEditor.changeEditMode(BaseEditor.MODE_OFF);
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;

        mNavigationIconHints = hints;
        getBackButtonIcon().setImeVisible(backAlt);

        final boolean showImeButton = ((hints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
        switch(mImeHintMode) {
            case IME_HINT_MODE_HIDDEN: // always hidden
                getImeSwitchButton().setVisibility(View.INVISIBLE);
                setImeArrowsVisibility(mCurrentView, View.INVISIBLE);
                break;
            case IME_HINT_MODE_PICKER:
                getHiddenContext().findViewWithTag(Res.Softkey.IME_SWITCHER).setVisibility(INVISIBLE);
                getImeSwitchButton().setVisibility(showImeButton ? View.VISIBLE : View.INVISIBLE);
                setImeArrowsVisibility(mCurrentView, View.INVISIBLE);
                break;
            default: // arrows
                getImeSwitchButton().setVisibility(View.INVISIBLE);
                setImeArrowsVisibility(mCurrentView, backAlt ? View.VISIBLE : View.INVISIBLE);
        }

        // Update menu button in case the IME state has changed.
        setScreenPinningVisibility();
        setMenuVisibility(mShowMenu, true);
        setDisabledFlags(mDisabledFlags, true);
    }

    @Override
    public void setDisabledFlags(int disabledFlags, boolean force) {
        super.setDisabledFlags(disabledFlags, force);
        mEditor.changeEditMode(BaseEditor.MODE_OFF);

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);

        getBackButton().setVisibility(disableBack ? View.INVISIBLE : View.VISIBLE);
        getHomeButton().setVisibility(disableHome ? View.INVISIBLE : View.VISIBLE);

        // if any stock buttons are disabled, it's likely proper
        // to disable custom buttons as well
        for (String buttonTag : mCurrentSequence) {
            SmartButtonView v = findCurrentButton(buttonTag);
            if (v != null && v != getBackButton() && v != getHomeButton()) {
                if (disableHome || disableBack || disableRecent) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private void setScreenPinningVisibility() {
        final boolean imeOccupying = ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0)
                && mImeHintMode == IME_HINT_MODE_ARROWS;
        getScreenPinningButton().setVisibility(
                mScreenPinningEnabled && !imeOccupying ? VISIBLE : INVISIBLE);
    }

    @Override
    public void notifyScreenOn(boolean screenOn) {
        super.notifyScreenOn(screenOn);
        mEditor.notifyScreenOn(screenOn);
        ViewGroup hidden = (ViewGroup) getHiddenView().findViewWithTag(Res.Common.NAV_BUTTONS);
        for (String buttonTag : mCurrentSequence) {
            SmartButtonView v = findCurrentButton(buttonTag);
            if (v != null) {
                v.onScreenStateChanged(screenOn);
            }
            v = (SmartButtonView) hidden.findViewWithTag(buttonTag);
            if (v != null) {
                v.onScreenStateChanged(screenOn);
            }
        }
    }

    @Override
    protected void onKeyguardShowing(boolean showing) {
        mEditor.setKeyguardShowing(showing);
    }

    @Override
    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    @Override
    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show)
            return;
        mEditor.changeEditMode(BaseEditor.MODE_OFF);
        mShowMenu = show;

        // Only show Menu if IME switcher not shown.
        final boolean shouldShow = mShowMenu &&
                ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0);
        getMenuButton().setVisibility(shouldShow ? View.VISIBLE : View.INVISIBLE);
    }

    void recreateLayouts() {
        mCurrentSequence.clear();
        ArrayList<ButtonConfig> buttonConfigs = Config.getConfig(getContext(),
                ActionConstants.getDefaults(ActionConstants.SMARTBAR));
        recreateButtonLayout(buttonConfigs, false, true);
        recreateButtonLayout(buttonConfigs, true, false);
        mContextLeft = mCurrentView.findViewWithTag(Res.Softkey.CONTEXT_VIEW_LEFT);
        mContextRight = mCurrentView.findViewWithTag(Res.Softkey.CONTEXT_VIEW_RIGHT);
        mCurrentContext = mHasLeftContext ? mContextLeft : mContextRight;
        updateCurrentIcons();
        setDisabledFlags(mDisabledFlags, true);
        setScreenPinningVisibility();
        setMenuVisibility(mShowMenu, true);
        setNavigationIconHints(mNavigationIconHints, true);
        updateAnimationStyle();
    }

    @Override
    protected void onDispose() {
        mEditor.unregister();
    }

    @Override
    public void reorient() {
        mEditor.prepareToReorient();
        super.reorient();
        mBarTransitions.init();
        mEditor.reorient(mCurrentView == mRot90);
        mContextLeft = mCurrentView.findViewWithTag(Res.Softkey.CONTEXT_VIEW_LEFT);
        mContextRight = mCurrentView.findViewWithTag(Res.Softkey.CONTEXT_VIEW_RIGHT);
        mCurrentContext = mHasLeftContext ? mContextLeft : mContextRight;
        setDisabledFlags(mDisabledFlags, true);
        setScreenPinningVisibility();
        setMenuVisibility(mShowMenu, true);
        setNavigationIconHints(mNavigationIconHints, true);
    }

    private void updateContextLayoutSettings() {
        boolean onLeft = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                "smartbar_context_menu_mode", 0, UserHandle.USER_CURRENT) == 1;
        if (mHasLeftContext != onLeft) {
            getMenuButton().setVisibility(INVISIBLE);
            getImeSwitchButton().setVisibility(INVISIBLE);
            getScreenPinningButton().setVisibility(INVISIBLE);
            getHiddenContext().findViewWithTag(Res.Softkey.MENU_BUTTON).setVisibility(INVISIBLE);
            getHiddenContext().findViewWithTag(Res.Softkey.IME_SWITCHER).setVisibility(INVISIBLE);
            getHiddenContext().findViewWithTag(Res.Softkey.STOP_SCREENPINNING).setVisibility(INVISIBLE);
            mHasLeftContext = onLeft;
            mCurrentContext = mHasLeftContext ? mContextLeft : mContextRight;
            setDisabledFlags(mDisabledFlags, true);
            setScreenPinningVisibility();
            setMenuVisibility(mShowMenu, true);
            setNavigationIconHints(mNavigationIconHints, true);
        }
    }

    private void updateImeHintModeSettings() {
        mImeHintMode = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                "smartbar_ime_hint_mode", IME_HINT_MODE_ARROWS, UserHandle.USER_CURRENT);
    }

    private void updateAnimationStyle() {
        mButtonAnimationStyle = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                "smartbar_button_animation_style", SmartButtonView.ANIM_STYLE_RIPPLE, UserHandle.USER_CURRENT);
        ViewGroup hidden = (ViewGroup) getHiddenView().findViewWithTag(Res.Common.NAV_BUTTONS);
        for (String buttonTag : mCurrentSequence) {
            SmartButtonView v = findCurrentButton(buttonTag);
            if (v != null) {
                v.setAnimationStyle(mButtonAnimationStyle);
            }
            v = (SmartButtonView) hidden.findViewWithTag(buttonTag);
            if (v != null) {
                v.setAnimationStyle(mButtonAnimationStyle);
            }
        }
    }

    private void refreshImeHintMode() {
        getMenuButton().setVisibility(INVISIBLE);
        getImeSwitchButton().setVisibility(INVISIBLE);
        getScreenPinningButton().setVisibility(INVISIBLE);
        getHiddenContext().findViewWithTag(Res.Softkey.MENU_BUTTON).setVisibility(INVISIBLE);
        getHiddenContext().findViewWithTag(Res.Softkey.IME_SWITCHER).setVisibility(INVISIBLE);
        getHiddenContext().findViewWithTag(Res.Softkey.STOP_SCREENPINNING).setVisibility(INVISIBLE);
        setNavigationIconHints(mNavigationIconHints, true);
    }

    void recreateButtonLayout(ArrayList<ButtonConfig> buttonConfigs, boolean landscape,
            boolean updateCurrentButtons) {
        int extraKeyWidth = getContext().getResources().getDimensionPixelSize(R.dimen.navigation_extra_key_width);
        int extraKeyHeight = getContext().getResources().getDimensionPixelSize(R.dimen.navigation_extra_key_height);

        LinearLayout navButtonLayout = (LinearLayout) (landscape ? mRot90
                .findViewWithTag(Res.Common.NAV_BUTTONS) : mRot0
                .findViewWithTag(Res.Common.NAV_BUTTONS));

        LinearLayout lightsOut = (LinearLayout) (landscape ? mRot90
                .findViewWithTag(Res.Common.LIGHTS_OUT) : mRot0
                .findViewWithTag(Res.Common.LIGHTS_OUT));

        navButtonLayout.removeAllViews();
        lightsOut.removeAllViews();

        if (buttonConfigs == null) {
            buttonConfigs = Config.getConfig(getContext(),
                    ActionConstants.getDefaults(ActionConstants.SMARTBAR));
        }

        // left context frame layout
        FrameLayout leftContext = generateContextKeyLayout(landscape,
                Res.Softkey.CONTEXT_VIEW_LEFT,
                extraKeyWidth, extraKeyHeight);
        SmartBarHelper.addViewToRoot(navButtonLayout, leftContext, landscape);
        SmartBarHelper.addLightsOutButton(getContext(), lightsOut, leftContext, landscape, true);

        // tablets get a spacer here
        if (BaseNavigationBar.sIsTablet) {
            SmartBarHelper.addViewToRoot(navButtonLayout, SmartBarHelper.makeSeparator(getContext()),
                    landscape);
            SmartBarHelper.addLightsOutButton(getContext(), lightsOut,
                    SmartBarHelper.makeSeparator(getContext()), landscape, true);
        }

        // softkey buttons
        ButtonConfig buttonConfig;
        int dimen = SmartBarHelper.getButtonSize(getContext(), buttonConfigs.size(), landscape);

        for (int j = 0; j < buttonConfigs.size(); j++) {
            buttonConfig = buttonConfigs.get(j);
            SmartButtonView v = SmartBarHelper.generatePrimaryKey(getContext(), this, landscape, buttonConfig);
            SmartBarHelper.updateButtonSize(v, dimen, landscape);
            SmartBarHelper.addViewToRoot(navButtonLayout, v, landscape);
            SmartBarHelper.addLightsOutButton(getContext(), lightsOut, v, landscape, false);

            // only add once for master sequence holder
            if (updateCurrentButtons) {
                mCurrentSequence.add((String) v.getTag());
            }

            // phones get a spacer between each button
            // tablets get a spacer before first and after last
            if (j != buttonConfigs.size() - 1 && !BaseNavigationBar.sIsTablet) {
                // adding spacers between buttons on phones
                SmartBarHelper.addViewToRoot(navButtonLayout,
                        SmartBarHelper.makeSeparator(getContext()), landscape);
                SmartBarHelper.addLightsOutButton(getContext(), lightsOut,
                        SmartBarHelper.makeSeparator(getContext()), landscape, true);
            }
            if (j == buttonConfigs.size() - 1 && BaseNavigationBar.sIsTablet) {
                // adding spacers after last button on tablets
                SmartBarHelper.addViewToRoot(navButtonLayout,
                        SmartBarHelper.makeSeparator(getContext()), landscape);
                SmartBarHelper.addLightsOutButton(getContext(), lightsOut,
                        SmartBarHelper.makeSeparator(getContext()), landscape, true);
            }
        }

        // right context frame layout
        FrameLayout rightContext = generateContextKeyLayout(landscape,
                Res.Softkey.CONTEXT_VIEW_RIGHT,
                extraKeyWidth, extraKeyHeight);
        SmartBarHelper.addViewToRoot(navButtonLayout, rightContext, landscape);
        SmartBarHelper.addLightsOutButton(getContext(), lightsOut, rightContext, landscape, true);
    }

    private FrameLayout generateContextKeyLayout(boolean landscape, String leftOrRight,
            int extraKeyWidth, int extraKeyHeight) {
        FrameLayout contextLayout = new FrameLayout(getContext());
        contextLayout.setLayoutParams(new LinearLayout.LayoutParams(
                landscape && !BaseNavigationBar.sIsTablet ? LayoutParams.MATCH_PARENT
                        : extraKeyWidth, landscape && !BaseNavigationBar.sIsTablet ? extraKeyHeight
                        : LayoutParams.MATCH_PARENT));
        contextLayout.setTag(leftOrRight);

        SmartButtonView menuKeyView = generateContextKey(landscape, Res.Softkey.MENU_BUTTON);
        contextLayout.addView(menuKeyView);

        SmartButtonView imeChanger = generateContextKey(landscape, Res.Softkey.IME_SWITCHER);
        contextLayout.addView(imeChanger);

        SmartButtonView stopScreenpinning = generateContextKey(landscape, Res.Softkey.STOP_SCREENPINNING);
        contextLayout.addView(stopScreenpinning);

        if (TextUtils.equals(Res.Softkey.CONTEXT_VIEW_LEFT, leftOrRight)) {
            SmartButtonView imeArrowLeft = generateContextKey(landscape, Res.Softkey.IME_ARROW_LEFT);
            contextLayout.addView(imeArrowLeft);
        } else if (TextUtils.equals(Res.Softkey.CONTEXT_VIEW_RIGHT, leftOrRight)) {
            SmartButtonView imeArrowRight = generateContextKey(landscape, Res.Softkey.IME_ARROW_RIGHT);
            contextLayout.addView(imeArrowRight);
        }

        return contextLayout;
    }

    private SmartButtonView generateContextKey(boolean landscape, String tag) {
        SmartButtonView v = new SmartButtonView(getContext(), this);
        ButtonConfig buttonConfig = new ButtonConfig(getContext());
        ActionConfig actionConfig;

        int extraKeyWidth = getContext().getResources().getDimensionPixelSize(R.dimen.navigation_extra_key_width);
        int extraKeyHeight = getContext().getResources().getDimensionPixelSize(R.dimen.navigation_extra_key_height);

        v.setLayoutParams(new FrameLayout.LayoutParams(
                landscape && !BaseNavigationBar.sIsTablet ? LayoutParams.MATCH_PARENT : extraKeyWidth,
                landscape && !BaseNavigationBar.sIsTablet ? extraKeyHeight : LayoutParams.MATCH_PARENT));
        v.loadRipple();
        v.setScaleType(ScaleType.CENTER_INSIDE);

        if (tag.equals(Res.Softkey.MENU_BUTTON)) {
            actionConfig = new ActionConfig(getContext(), ActionHandler.SYSTEMUI_TASK_MENU);
        } else if (tag.equals(Res.Softkey.IME_SWITCHER)) {
            actionConfig = new ActionConfig(getContext(), ActionHandler.SYSTEMUI_TASK_IME_SWITCHER);
        } else if (tag.equals(Res.Softkey.IME_ARROW_LEFT)) {
            actionConfig = new ActionConfig(getContext(), ActionHandler.SYSTEMUI_TASK_IME_NAVIGATION_LEFT);
        } else if (tag.equals(Res.Softkey.IME_ARROW_RIGHT)) {
            actionConfig = new ActionConfig(getContext(), ActionHandler.SYSTEMUI_TASK_IME_NAVIGATION_RIGHT);
        } else {
            actionConfig = new ActionConfig(getContext(),
                    ActionHandler.SYSTEMUI_TASK_STOP_SCREENPINNING);
        }

        buttonConfig.setActionConfig(actionConfig, ActionConfig.PRIMARY);
        buttonConfig.setTag(tag);
        v.setButtonConfig(buttonConfig);
        v.setVisibility(View.INVISIBLE);
        setButtonDrawable(v);
        v.setContentDescription(buttonConfig.getActionConfig(ActionConfig.PRIMARY).getLabel());
        v.setAnimationStyle(SmartButtonView.ANIM_STYLE_RIPPLE);
        return v;
    }

    boolean isBarPulseFaded() {
        if (mPulse == null) {
            return false;
        } else {
            return mPulse.shouldDrawPulse();
        }
    }

    @Override
    public boolean onStartPulse(Animation animatePulseIn) {
        if (mEditor.getMode() == BaseEditor.MODE_ON) {
            mEditor.changeEditMode(BaseEditor.MODE_OFF);
        }
        final View currentNavButtons = getCurrentView().findViewWithTag(Res.Common.NAV_BUTTONS);
        final View hiddenNavButtons = getHiddenView().findViewWithTag(Res.Common.NAV_BUTTONS);

        // no need to animate the GONE view, but keep alpha inline since onStartPulse
        // is a oneshot call
        hiddenNavButtons.setAlpha(PULSE_ALPHA_FADE);
        currentNavButtons.animate()
                .alpha(PULSE_ALPHA_FADE)
                .setDuration(PULSE_FADE_OUT_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        // shouldn't be null, mPulse just called into us
                        if (mPulse != null) {
                            mPulse.turnOnPulse();
                        }
                    }
                })
                .start();
        return true;
    }

    @Override
    public void onStopPulse(Animation animatePulseOut) {
        final View currentNavButtons = getCurrentView().findViewWithTag(Res.Common.NAV_BUTTONS);
        final View hiddenNavButtons = getHiddenView().findViewWithTag(Res.Common.NAV_BUTTONS);

        hiddenNavButtons.setAlpha(1.0f);
        currentNavButtons.animate()
                .alpha(1.0f)
                .setDuration(PULSE_FADE_IN_DURATION)
                .start();
    }
}
