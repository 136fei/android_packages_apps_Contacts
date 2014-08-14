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
package com.android.contacts.quickcontact;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.CardView;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.ChangeScroll;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.Transition.TransitionListener;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.contacts.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Display entries in a LinearLayout that can be expanded to show all entries.
 */
public class ExpandingEntryCardView extends CardView {

    private static final String TAG = "ExpandingEntryCardView";
    private static final int DURATION_EXPAND_ANIMATION_FADE_IN = 200;
    private static final int DELAY_EXPAND_ANIMATION_FADE_IN = 100;

    public static final int DURATION_EXPAND_ANIMATION_CHANGE_BOUNDS = 300;
    public static final int DURATION_COLLAPSE_ANIMATION_CHANGE_BOUNDS = 300;

    /**
     * Entry data.
     */
    public static final class Entry {

        private final int mId;
        private final Drawable mIcon;
        private final String mHeader;
        private final String mSubHeader;
        private final Drawable mSubHeaderIcon;
        private final String mText;
        private final Drawable mTextIcon;
        private final Intent mIntent;
        private final Drawable mAlternateIcon;
        private final Intent mAlternateIntent;
        private final String mAlternateContentDescription;
        private final boolean mShouldApplyColor;
        private final boolean mIsEditable;
        private final EntryContextMenuInfo mEntryContextMenuInfo;

        public Entry(int id, Drawable icon, String header, String subHeader, String text,
                Intent intent, Drawable alternateIcon, Intent alternateIntent,
                String alternateContentDescription, boolean shouldApplyColor,
                boolean isEditable, EntryContextMenuInfo entryContextMenuInfo) {
            this(id, icon, header, subHeader, null, text, null, intent, alternateIcon,
                    alternateIntent, alternateContentDescription, shouldApplyColor, isEditable,
                    entryContextMenuInfo);
        }

        public Entry(int id, Drawable mainIcon, String header, String subHeader,
                Drawable subHeaderIcon, String text, Drawable textIcon, Intent intent,
                Drawable alternateIcon, Intent alternateIntent, String alternateContentDescription,
                boolean shouldApplyColor, boolean isEditable,
                EntryContextMenuInfo entryContextMenuInfo) {
            mId = id;
            mIcon = mainIcon;
            mHeader = header;
            mSubHeader = subHeader;
            mSubHeaderIcon = subHeaderIcon;
            mText = text;
            mTextIcon = textIcon;
            mIntent = intent;
            mAlternateIcon = alternateIcon;
            mAlternateIntent = alternateIntent;
            mAlternateContentDescription = alternateContentDescription;
            mShouldApplyColor = shouldApplyColor;
            mIsEditable = isEditable;
            mEntryContextMenuInfo = entryContextMenuInfo;
        }

        Drawable getIcon() {
            return mIcon;
        }

        String getHeader() {
            return mHeader;
        }

        String getSubHeader() {
            return mSubHeader;
        }

        Drawable getSubHeaderIcon() {
            return mSubHeaderIcon;
        }

        public String getText() {
            return mText;
        }

        Drawable getTextIcon() {
            return mTextIcon;
        }

        Intent getIntent() {
            return mIntent;
        }

        Drawable getAlternateIcon() {
            return mAlternateIcon;
        }

        Intent getAlternateIntent() {
            return mAlternateIntent;
        }

        String getAlternateContentDescription() {
            return mAlternateContentDescription;
        }

        boolean shouldApplyColor() {
            return mShouldApplyColor;
        }

        boolean isEditable() {
            return mIsEditable;
        }

        int getId() {
            return mId;
        }

        EntryContextMenuInfo getEntryContextMenuInfo() {
            return mEntryContextMenuInfo;
        }
    }

    public interface ExpandingEntryCardViewListener {
        void onCollapse(int heightDelta);
        void onExpand(int heightDelta);
    }

    private View mExpandCollapseButton;
    private TextView mExpandCollapseTextView;
    private TextView mTitleTextView;
    private CharSequence mExpandButtonText;
    private CharSequence mCollapseButtonText;
    private OnClickListener mOnClickListener;
    private OnCreateContextMenuListener mOnCreateContextMenuListener;
    private boolean mIsExpanded = false;
    /**
     * The max number of entries to show in a collapsed card. If there are less entries passed in,
     * then they are all shown.
     */
    private int mCollapsedEntriesCount;
    private ExpandingEntryCardViewListener mListener;
    private List<List<Entry>> mEntries;
    private int mNumEntries = 0;
    private boolean mAllEntriesInflated = false;
    private List<List<View>> mEntryViews;
    private LinearLayout mEntriesViewGroup;
    private final ImageView mExpandCollapseArrow;
    private int mThemeColor;
    private ColorFilter mThemeColorFilter;
    private boolean mIsAlwaysExpanded;
    /** The ViewGroup to run the expand/collapse animation on */
    private ViewGroup mAnimationViewGroup;
    private LinearLayout mBadgeContainer;
    private final List<ImageView> mBadges;
    private LinearLayout mContainer;

    private final OnClickListener mExpandCollapseButtonListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mIsExpanded) {
                collapse();
            } else {
                expand();
            }
        }
    };

    public ExpandingEntryCardView(Context context) {
        this(context, null);
    }

    public ExpandingEntryCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater inflater = LayoutInflater.from(context);
        View expandingEntryCardView = inflater.inflate(R.layout.expanding_entry_card_view, this);
        mEntriesViewGroup = (LinearLayout)
                expandingEntryCardView.findViewById(R.id.content_area_linear_layout);
        mTitleTextView = (TextView) expandingEntryCardView.findViewById(R.id.title);
        mContainer = (LinearLayout) expandingEntryCardView.findViewById(R.id.container);

        mExpandCollapseButton = inflater.inflate(
                R.layout.quickcontact_expanding_entry_card_button, this, false);
        mExpandCollapseTextView = (TextView) mExpandCollapseButton.findViewById(R.id.text);
        mExpandCollapseArrow = (ImageView) mExpandCollapseButton.findViewById(R.id.arrow);
        mExpandCollapseButton.setOnClickListener(mExpandCollapseButtonListener);
        mBadgeContainer = (LinearLayout) mExpandCollapseButton.findViewById(R.id.badge_container);

        mBadges = new ArrayList<ImageView>();
    }

    /**
     * Sets the Entry list to display.
     *
     * @param entries The Entry list to display.
     */
    public void initialize(List<List<Entry>> entries, int numInitialVisibleEntries,
            boolean isExpanded, boolean isAlwaysExpanded,
            ExpandingEntryCardViewListener listener, ViewGroup animationViewGroup) {
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mIsExpanded = isExpanded;
        mIsAlwaysExpanded = isAlwaysExpanded;
        // If isAlwaysExpanded is true, mIsExpanded should be true
        mIsExpanded |= mIsAlwaysExpanded;
        mEntryViews = new ArrayList<List<View>>(entries.size());
        mEntries = entries;
        mNumEntries = 0;
        mAllEntriesInflated = false;
        for (List<Entry> entryList : mEntries) {
            mNumEntries += entryList.size();
            mEntryViews.add(new ArrayList<View>());
        }
        mCollapsedEntriesCount = Math.min(numInitialVisibleEntries, mNumEntries);
        mListener = listener;
        mAnimationViewGroup = animationViewGroup;

        if (mIsExpanded) {
            updateExpandCollapseButton(getCollapseButtonText(), /* duration = */ 0);
            inflateAllEntries(layoutInflater);
        } else {
            updateExpandCollapseButton(getExpandButtonText(), /* duration = */ 0);
            inflateInitialEntries(layoutInflater);
        }
        insertEntriesIntoViewGroup();
        applyColor();
    }

    /**
     * Sets the text for the expand button.
     *
     * @param expandButtonText The expand button text.
     */
    public void setExpandButtonText(CharSequence expandButtonText) {
        mExpandButtonText = expandButtonText;
        if (mExpandCollapseTextView != null && !mIsExpanded) {
            mExpandCollapseTextView.setText(expandButtonText);
        }
    }

    /**
     * Sets the text for the expand button.
     *
     * @param expandButtonText The expand button text.
     */
    public void setCollapseButtonText(CharSequence expandButtonText) {
        mCollapseButtonText = expandButtonText;
        if (mExpandCollapseTextView != null && mIsExpanded) {
            mExpandCollapseTextView.setText(mCollapseButtonText);
        }
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        mOnClickListener = listener;
    }

    @Override
    public void setOnCreateContextMenuListener (OnCreateContextMenuListener listener) {
        mOnCreateContextMenuListener = listener;
    }

    private void insertEntriesIntoViewGroup() {
        mEntriesViewGroup.removeAllViews();

        if (mIsExpanded) {
            for (List<View> viewList : mEntryViews) {
                if (viewList != mEntryViews.get(0)) {
                    addSeparator(viewList.get(0));
                }
                for (View view : viewList) {
                    addEntry(view);
                }
            }
        } else {
            // We want to insert mCollapsedEntriesCount entries into the group. extraEntries is the
            // number of entries that need to be added that are not the head element of a list
            // to reach mCollapsedEntriesCount.
            int numInViewGroup = 0;
            int extraEntries = mCollapsedEntriesCount - mEntryViews.size();
            for (int i = 0; i < mEntryViews.size() && numInViewGroup < mCollapsedEntriesCount;
                    i++) {
                List<View> entryViewList = mEntryViews.get(i);
                if (i > 0) {
                    addSeparator(entryViewList.get(0));
                }
                addEntry(entryViewList.get(0));
                numInViewGroup++;
                // Insert entries in this list to hit mCollapsedEntriesCount.
                for (int j = 1;
                        j < entryViewList.size() && numInViewGroup < mCollapsedEntriesCount &&
                        extraEntries > 0;
                        j++) {
                    addEntry(entryViewList.get(j));
                    numInViewGroup++;
                    extraEntries--;
                }
            }
        }

        removeView(mExpandCollapseButton);
        if (mCollapsedEntriesCount < mNumEntries
                && mExpandCollapseButton.getParent() == null && !mIsAlwaysExpanded) {
            mContainer.addView(mExpandCollapseButton, -1);
        }
    }

    private void addEntry(View entry) {
        mEntriesViewGroup.addView(entry);
    }

    private void addSeparator(View entry) {
        View separator = new View(getContext());
        Resources res = getResources();

        separator.setBackgroundColor(res.getColor(
                R.color.expanding_entry_card_item_separator_color));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                res.getDimensionPixelSize(R.dimen.expanding_entry_card_item_separator_height));
        // The separator is aligned with the text in the entry. This is offset by a default
        // margin. If there is an icon present, the icon's width and margin are added
        int marginStart = res.getDimensionPixelSize(
                R.dimen.expanding_entry_card_item_padding_start);
        ImageView entryIcon = (ImageView) entry.findViewById(R.id.icon);
        if (entryIcon.getVisibility() == View.VISIBLE) {
            int imageWidthAndMargin =
                    res.getDimensionPixelSize(R.dimen.expanding_entry_card_item_icon_width) +
                    res.getDimensionPixelSize(R.dimen.expanding_entry_card_item_image_spacing);
            marginStart += imageWidthAndMargin;
        }
        layoutParams.setMarginStart(marginStart);
        separator.setLayoutParams(layoutParams);
        mEntriesViewGroup.addView(separator);
    }

    private CharSequence getExpandButtonText() {
        if (!TextUtils.isEmpty(mExpandButtonText)) {
            return mExpandButtonText;
        } else {
            // Default to "See more".
            return getResources().getText(R.string.expanding_entry_card_view_see_more);
        }
    }

    private CharSequence getCollapseButtonText() {
        if (!TextUtils.isEmpty(mCollapseButtonText)) {
            return mCollapseButtonText;
        } else {
            // Default to "See less".
            return getResources().getText(R.string.expanding_entry_card_view_see_less);
        }
    }

    /**
     * Inflates the initial entries to be shown.
     */
    private void inflateInitialEntries(LayoutInflater layoutInflater) {
        // If the number of collapsed entries equals total entries, inflate all
        if (mCollapsedEntriesCount == mNumEntries) {
            inflateAllEntries(layoutInflater);
        } else {
            // Otherwise inflate the top entry from each list
            // extraEntries is used to add extra entries until mCollapsedEntriesCount is reached.
            int numInflated = 0;
            int extraEntries = mCollapsedEntriesCount - mEntries.size();
            for (int i = 0; i < mEntries.size() && numInflated < mCollapsedEntriesCount; i++) {
                List<Entry> entryList = mEntries.get(i);
                List<View> entryViewList = mEntryViews.get(i);

                entryViewList.add(createEntryView(layoutInflater, entryList.get(0),
                        /* showIcon = */ View.VISIBLE));
                numInflated++;
                // Inflate entries in this list to hit mCollapsedEntriesCount.
                for (int j = 1; j < entryList.size() && numInflated < mCollapsedEntriesCount &&
                        extraEntries > 0; j++) {
                    entryViewList.add(createEntryView(layoutInflater, entryList.get(j),
                            /* showIcon = */ View.VISIBLE));
                    numInflated++;
                    extraEntries--;
                }
            }
        }
    }

    /**
     * Inflates all entries.
     */
    private void inflateAllEntries(LayoutInflater layoutInflater) {
        if (mAllEntriesInflated) {
            return;
        }
        for (int i = 0; i < mEntries.size(); i++) {
            List<Entry> entryList = mEntries.get(i);
            List<View> viewList = mEntryViews.get(i);
            for (int j = viewList.size(); j < entryList.size(); j++) {
                final int iconVisibility;
                final Entry entry = entryList.get(j);
                // If the entry does not have an icon, mark gone. Else if it has an icon, show
                // for the first Entry in the list only
                if (entry.getIcon() == null) {
                    iconVisibility = View.GONE;
                } else if (j == 0) {
                    iconVisibility = View.VISIBLE;
                } else {
                    iconVisibility = View.INVISIBLE;
                }
                viewList.add(createEntryView(layoutInflater, entry, iconVisibility));
            }
        }
        mAllEntriesInflated = true;
    }

    public void setColorAndFilter(int color, ColorFilter colorFilter) {
        mThemeColor = color;
        mThemeColorFilter = colorFilter;
        applyColor();
    }

    public void setEntryHeaderColor(int color) {
        if (mEntries != null) {
            for (List<View> entryList : mEntryViews) {
                for (View entryView : entryList) {
                    TextView header = (TextView) entryView.findViewById(R.id.header);
                    if (header != null) {
                        header.setTextColor(color);
                    }
                }
            }
        }
    }

    /**
     * The ColorFilter is passed in along with the color so that a new one only needs to be created
     * once for the entire activity.
     * 1. Title
     * 2. Entry icons
     * 3. Expand/Collapse Text
     * 4. Expand/Collapse Button
     */
    public void applyColor() {
        if (mThemeColor != 0 && mThemeColorFilter != null) {
            // Title
            if (mTitleTextView != null) {
                mTitleTextView.setTextColor(mThemeColor);
            }

            // Entry icons
            if (mEntries != null) {
                for (List<Entry> entryList : mEntries) {
                    for (Entry entry : entryList) {
                        if (entry.shouldApplyColor()) {
                            Drawable icon = entry.getIcon();
                            if (icon != null) {
                                icon.setColorFilter(mThemeColorFilter);
                            }
                        }
                        Drawable alternateIcon = entry.getAlternateIcon();
                        if (alternateIcon != null) {
                            alternateIcon.setColorFilter(mThemeColorFilter);
                        }
                    }
                }
            }

            // Expand/Collapse
            mExpandCollapseTextView.setTextColor(mThemeColor);
            mExpandCollapseArrow.setColorFilter(mThemeColorFilter);
        }
    }

    private View createEntryView(LayoutInflater layoutInflater, final Entry entry,
            int iconVisibility) {
        final EntryView view = (EntryView) layoutInflater.inflate(
                R.layout.expanding_entry_card_item, this, false);

        view.setContextMenuInfo(entry.getEntryContextMenuInfo());

        final ImageView icon = (ImageView) view.findViewById(R.id.icon);
        icon.setVisibility(iconVisibility);
        if (entry.getIcon() != null) {
            icon.setImageDrawable(entry.getIcon());
        }
        final TextView header = (TextView) view.findViewById(R.id.header);
        if (!TextUtils.isEmpty(entry.getHeader())) {
            header.setText(entry.getHeader());
        } else {
            header.setVisibility(View.GONE);
        }

        final TextView subHeader = (TextView) view.findViewById(R.id.sub_header);
        if (!TextUtils.isEmpty(entry.getSubHeader())) {
            subHeader.setText(entry.getSubHeader());
        } else {
            subHeader.setVisibility(View.GONE);
        }

        final ImageView subHeaderIcon = (ImageView) view.findViewById(R.id.icon_sub_header);
        if (entry.getSubHeaderIcon() != null) {
            subHeaderIcon.setImageDrawable(entry.getSubHeaderIcon());
        } else {
            subHeaderIcon.setVisibility(View.GONE);
        }

        final TextView text = (TextView) view.findViewById(R.id.text);
        if (!TextUtils.isEmpty(entry.getText())) {
            text.setText(entry.getText());
        } else {
            text.setVisibility(View.GONE);
        }

        final ImageView textIcon = (ImageView) view.findViewById(R.id.icon_text);
        if (entry.getTextIcon() != null) {
            textIcon.setImageDrawable(entry.getTextIcon());
        } else {
            textIcon.setVisibility(View.GONE);
        }

        if (entry.getIntent() != null) {
            view.setOnClickListener(mOnClickListener);
            view.setTag(new EntryTag(entry.getId(), entry.getIntent()));
        }

        // If only the header is visible, add a top margin to match icon's top margin.
        // Also increase the space below the header for visual comfort.
        if (header.getVisibility() == View.VISIBLE && subHeader.getVisibility() == View.GONE &&
                text.getVisibility() == View.GONE) {
            RelativeLayout.LayoutParams headerLayoutParams =
                    (RelativeLayout.LayoutParams) header.getLayoutParams();
            headerLayoutParams.topMargin = (int) (getResources().getDimension(
                    R.dimen.expanding_entry_card_item_header_only_margin_top));
            headerLayoutParams.bottomMargin += (int) (getResources().getDimension(
                    R.dimen.expanding_entry_card_item_header_only_margin_bottom));
            header.setLayoutParams(headerLayoutParams);
        }

        final ImageView alternateIcon = (ImageView) view.findViewById(R.id.icon_alternate);
        if (entry.getAlternateIcon() != null && entry.getAlternateIntent() != null) {
            alternateIcon.setImageDrawable(entry.getAlternateIcon());
            alternateIcon.setOnClickListener(mOnClickListener);
            alternateIcon.setTag(new EntryTag(entry.getId(), entry.getAlternateIntent()));
            alternateIcon.setVisibility(View.VISIBLE);
            alternateIcon.setContentDescription(entry.getAlternateContentDescription());

            // Expand the clickable area for alternate icon to be top to bottom and to end edge
            // of the entry view
            view.post(new Runnable() {
                @Override
                public void run() {
                    final Rect alternateIconRect = new Rect();
                    alternateIcon.getHitRect(alternateIconRect);

                    alternateIconRect.bottom = view.getHeight();
                    alternateIconRect.top = 0;
                    if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        alternateIconRect.left = 0;
                    } else {
                        alternateIconRect.right = view.getWidth();
                    }
                    final TouchDelegate touchDelegate =
                            new TouchDelegate(alternateIconRect, alternateIcon);
                    view.setTouchDelegate(touchDelegate);
                }
            });
        }

        // Decrease margin for entries that have an invisible icon
        if (iconVisibility == View.INVISIBLE) {
            view.setPaddingRelative(view.getPaddingStart(), 0, view.getPaddingEnd(),
                    view.getPaddingBottom());
        }


        view.setOnCreateContextMenuListener(mOnCreateContextMenuListener);

        return view;
    }

    private void updateExpandCollapseButton(CharSequence buttonText, long duration) {
        if (mIsExpanded) {
            final ObjectAnimator animator = ObjectAnimator.ofFloat(mExpandCollapseArrow,
                    "rotation", 180);
            animator.setDuration(duration);
            animator.start();
        } else {
            final ObjectAnimator animator = ObjectAnimator.ofFloat(mExpandCollapseArrow,
                    "rotation", 0);
            animator.setDuration(duration);
            animator.start();
        }
        updateBadges();

        mExpandCollapseTextView.setText(buttonText);
    }

    private void updateBadges() {
        if (mIsExpanded) {
            mBadgeContainer.removeAllViews();
        } else {
            // Inflate badges if not yet created
            if (mBadges.size() < mEntries.size() - mCollapsedEntriesCount) {
                for (int i = mCollapsedEntriesCount; i < mEntries.size(); i++) {
                    Drawable badgeDrawable = mEntries.get(i).get(0).getIcon();
                    if (badgeDrawable != null) {
                        ImageView badgeView = new ImageView(getContext());
                        LinearLayout.LayoutParams badgeViewParams = new LinearLayout.LayoutParams(
                                (int) getResources().getDimension(
                                        R.dimen.expanding_entry_card_item_icon_width),
                                (int) getResources().getDimension(
                                        R.dimen.expanding_entry_card_item_icon_height));
                        badgeViewParams.setMarginEnd((int) getResources().getDimension(
                                R.dimen.expanding_entry_card_badge_separator_margin));
                        badgeView.setLayoutParams(badgeViewParams);
                        badgeView.setImageDrawable(badgeDrawable);
                        mBadges.add(badgeView);
                    }
                }
            }
            mBadgeContainer.removeAllViews();
            for (ImageView badge : mBadges) {
                mBadgeContainer.addView(badge);
            }
        }
    }

    private void expand() {
        ChangeBounds boundsTransition = new ChangeBounds();
        boundsTransition.setDuration(DURATION_EXPAND_ANIMATION_CHANGE_BOUNDS);

        Fade fadeIn = new Fade(Fade.IN);
        fadeIn.setDuration(DURATION_EXPAND_ANIMATION_FADE_IN);
        fadeIn.setStartDelay(DELAY_EXPAND_ANIMATION_FADE_IN);

        TransitionSet transitionSet = new TransitionSet();
        transitionSet.addTransition(boundsTransition);
        transitionSet.addTransition(fadeIn);

        final ViewGroup transitionViewContainer = mAnimationViewGroup == null ?
                this : mAnimationViewGroup;

        transitionSet.addListener(new TransitionListener() {
            @Override
            public void onTransitionStart(Transition transition) {
                // The listener is used to turn off suppressing, the proper delta is not necessary
                mListener.onExpand(0);
            }

            @Override
            public void onTransitionEnd(Transition transition) {
            }

            @Override
            public void onTransitionCancel(Transition transition) {
            }

            @Override
            public void onTransitionPause(Transition transition) {
            }

            @Override
            public void onTransitionResume(Transition transition) {
            }
        });

        TransitionManager.beginDelayedTransition(transitionViewContainer, transitionSet);

        mIsExpanded = true;
        // In order to insert new entries, we may need to inflate them for the first time
        inflateAllEntries(LayoutInflater.from(getContext()));
        insertEntriesIntoViewGroup();
        updateExpandCollapseButton(getCollapseButtonText(),
                DURATION_EXPAND_ANIMATION_CHANGE_BOUNDS);
    }

    private void collapse() {
        final int startingHeight = mEntriesViewGroup.getMeasuredHeight();
        mIsExpanded = false;
        updateExpandCollapseButton(getExpandButtonText(),
                DURATION_COLLAPSE_ANIMATION_CHANGE_BOUNDS);

        final ChangeBounds boundsTransition = new ChangeBounds();
        boundsTransition.setDuration(DURATION_COLLAPSE_ANIMATION_CHANGE_BOUNDS);

        final ChangeScroll scrollTransition = new ChangeScroll();
        scrollTransition.setDuration(DURATION_COLLAPSE_ANIMATION_CHANGE_BOUNDS);

        TransitionSet transitionSet = new TransitionSet();
        transitionSet.addTransition(boundsTransition);
        transitionSet.addTransition(scrollTransition);

        final ViewGroup transitionViewContainer = mAnimationViewGroup == null ?
                this : mAnimationViewGroup;

        boundsTransition.addListener(new TransitionListener() {
            @Override
            public void onTransitionStart(Transition transition) {
                /*
                 * onTransitionStart is called after the view hierarchy has been changed but before
                 * the animation begins.
                 */
                int finishingHeight = mEntriesViewGroup.getMeasuredHeight();
                mListener.onCollapse(startingHeight - finishingHeight);
            }

            @Override
            public void onTransitionEnd(Transition transition) {
            }

            @Override
            public void onTransitionCancel(Transition transition) {
            }

            @Override
            public void onTransitionPause(Transition transition) {
            }

            @Override
            public void onTransitionResume(Transition transition) {
            }
        });

        TransitionManager.beginDelayedTransition(transitionViewContainer, transitionSet);

        insertEntriesIntoViewGroup();
    }

    /**
     * Returns whether the view is currently in its expanded state.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * Sets the title text of this ExpandingEntryCardView.
     * @param title The title to set. A null title will result in the title being removed.
     */
    public void setTitle(String title) {
        if (mTitleTextView == null) {
            Log.e(TAG, "mTitleTextView is null");
        }
        if (title == null) {
            mTitleTextView.setVisibility(View.GONE);
            findViewById(R.id.title_separator).setVisibility(View.GONE);
        }
        mTitleTextView.setText(title);
        mTitleTextView.setVisibility(View.VISIBLE);
        findViewById(R.id.title_separator).setVisibility(View.VISIBLE);
    }

    public boolean shouldShow() {
        return mEntries != null && mEntries.size() > 0;
    }

    public static final class EntryView extends RelativeLayout {
        private EntryContextMenuInfo mEntryContextMenuInfo;

        public EntryView(Context context) {
            super(context);
        }

        public EntryView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void setContextMenuInfo(EntryContextMenuInfo info) {
            mEntryContextMenuInfo = info;
        }

        @Override
        protected ContextMenuInfo getContextMenuInfo() {
            return mEntryContextMenuInfo;
        }
    }

    public static final class EntryContextMenuInfo implements ContextMenuInfo {
        private final String mCopyText;
        private final String mCopyLabel;

        public EntryContextMenuInfo(String copyText, String copyLabel) {
            mCopyText = copyText;
            mCopyLabel = copyLabel;
        }

        public String getCopyText() {
            return mCopyText;
        }

        public String getCopyLabel() {
            return mCopyLabel;
        }
    }

    static final class EntryTag {
        private final int mId;
        private final Intent mIntent;

        public EntryTag(int id, Intent intent) {
            mId = id;
            mIntent = intent;
        }

        public int getId() {
            return mId;
        }

        public Intent getIntent() {
            return mIntent;
        }
    }
}
