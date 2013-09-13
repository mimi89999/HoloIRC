/*
 * Copyright 2013 Niek Haarman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fusionx.lightirc.adapters;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;

import com.haarman.listviewanimations.BaseAdapterDecorator;
import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorSet;
import com.nineoldandroids.animation.ObjectAnimator;

/**
 * A BaseAdapterDecorator class which applies multiple Animators at once to
 * views when they are first shown. The Animators applied include the animations
 * specified in getAnimators(ViewGroup, View), plus an alpha transition.
 */
public class IRCAnimationAdapter extends BaseAdapterDecorator {
    private static final long DEFAULTANIMATIONDELAYMILLIS = 100;
    private static final long DEFAULTANIMATIONDURATIONMILLIS = 300;
    private static final long INITIALDELAYMILLIS = 150;

    private SparseArray<AnimationInfo> mAnimators;
    private long mAnimationStartMillis;
    private int mLastAnimatedPosition;
    private boolean mHasParentAnimationAdapter;
    private boolean mShouldAnimate = true;

    public IRCAnimationAdapter(BaseAdapter baseAdapter) {
        super(baseAdapter);
        mAnimators = new SparseArray<>();

        mAnimationStartMillis = -1;
        mLastAnimatedPosition = baseAdapter.getCount();
    }

    /**
     * Call this method to reset animation status on all views. The next time
     * notifyDataSetChanged() is called on the base adapter, all views will
     * animate again. Will also call setShouldAnimate(true).
     */
    void reset() {
        mAnimators.clear();
        mLastAnimatedPosition = getDecoratedBaseAdapter().getCount();
        mAnimationStartMillis = -1;
        mShouldAnimate = true;

        if (getDecoratedBaseAdapter() instanceof IRCAnimationAdapter) {
            ((IRCAnimationAdapter) getDecoratedBaseAdapter()).reset();
        }
    }

    @Override
    public final View getView(int position, View convertView, ViewGroup parent) {
        boolean alreadyStarted = false;
        if (!mHasParentAnimationAdapter) {
            if (getAbsListView() == null) {
                throw new IllegalStateException("Call setListView() on this AnimationAdapter " +
                        "before setAdapter()!");
            }

            if (convertView != null) {
                alreadyStarted = cancelExistingAnimation(position, convertView);
            }
        }

        View itemView = super.getView(position, convertView, parent);

        if (!mHasParentAnimationAdapter && !alreadyStarted) {
            animateViewIfNecessary(position, itemView, parent);
        }
        return itemView;
    }

    @Override
    public View getHeaderView(int position, View convertView, ViewGroup parent) {
        return null;
    }

    private boolean cancelExistingAnimation(int position, View convertView) {
        boolean alreadyStarted = false;

        int hashCode = convertView.hashCode();
        AnimationInfo animationInfo = mAnimators.get(hashCode);
        if (animationInfo != null) {
            if (animationInfo.position != position) {
                animationInfo.animator.end();
                mAnimators.remove(hashCode);
            } else {
                alreadyStarted = true;
            }
        }

        return alreadyStarted;
    }

    private void animateViewIfNecessary(int position, View view, ViewGroup parent) {
        if (position > mLastAnimatedPosition && mShouldAnimate) {
            animateView(position, parent, view, false);
            mLastAnimatedPosition = position;
        }
    }

    private void animateView(int position, ViewGroup parent, View view, boolean isHeader) {
        if (mAnimationStartMillis == -1) {
            mAnimationStartMillis = System.currentTimeMillis();
        }

        hideView(view);

        Animator[] childAnimators;
        if (mDecoratedBaseAdapter instanceof IRCAnimationAdapter) {
            childAnimators = ((IRCAnimationAdapter) mDecoratedBaseAdapter).getAnimators(parent,
                    view);
        } else {
            childAnimators = new Animator[0];
        }
        Animator[] animators = getAnimators(parent, view);
        Animator alphaAnimator = ObjectAnimator.ofFloat(view, "alpha", 0, 1);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(concatAnimators(childAnimators, animators, alphaAnimator));
        set.setStartDelay(calculateAnimationDelay(isHeader));
        set.setDuration(getAnimationDurationMillis());
        set.start();

        mAnimators.put(view.hashCode(), new AnimationInfo(position, set));
    }

    private void hideView(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "alpha", 0);
        AnimatorSet set = new AnimatorSet();
        set.play(animator);
        set.setDuration(0);
        set.start();
    }

    private Animator[] concatAnimators(Animator[] childAnimators, Animator[] animators,
                                       Animator alphaAnimator) {
        Animator[] allAnimators = new Animator[childAnimators.length + animators.length + 1];
        int i;

        for (i = 0; i < animators.length; ++i) {
            allAnimators[i] = animators[i];
        }

        for (Animator childAnimator : childAnimators) {
            allAnimators[i] = childAnimator;
            ++i;
        }

        allAnimators[allAnimators.length - 1] = alphaAnimator;
        return allAnimators;
    }

    @SuppressLint("NewApi")
    private long calculateAnimationDelay(boolean isHeader) {
        long delay;
        int numberOfItems = getAbsListView().getLastVisiblePosition() - getAbsListView()
                .getFirstVisiblePosition();
        if (numberOfItems + 1 < mLastAnimatedPosition) {
            delay = getAnimationDelayMillis();

            if (getAbsListView() instanceof GridView && Build.VERSION.SDK_INT >= 11) {
                delay += getAnimationDelayMillis() * ((mLastAnimatedPosition + 1) % ((GridView)
                        getAbsListView()).getNumColumns());
            }
        } else {
            long delaySinceStart = (mLastAnimatedPosition + 1) * getAnimationDelayMillis();
            delay = mAnimationStartMillis + getInitialDelayMillis() + delaySinceStart - System
                    .currentTimeMillis();
            delay -= isHeader && mLastAnimatedPosition > 0 ? getAnimationDelayMillis() : 0;
        }
        // System.out.println(isHeader + ": " + delay);

        return Math.max(0, delay);
    }

    /**
     * Set whether this AnimationAdapter is encapsulated by another
     * AnimationAdapter. When this is set to true, this AnimationAdapter does
     * not apply any animations to the views. Should not be set explicitly, the
     * AnimationAdapter class manages this by itself.
     */
    public void setHasParentAnimationAdapter(boolean hasParentAnimationAdapter) {
        mHasParentAnimationAdapter = hasParentAnimationAdapter;
    }

    /**
     * Get the delay in milliseconds before the first animation should start. Defaults to {@value
     * #INITIALDELAYMILLIS}.
     */
    long getInitialDelayMillis() {
        return INITIALDELAYMILLIS;
    }

    long getAnimationDelayMillis() {
        return DEFAULTANIMATIONDELAYMILLIS;
    }

    long getAnimationDurationMillis() {
        return DEFAULTANIMATIONDURATIONMILLIS;
    }

    Animator[] getAnimators(ViewGroup parent, View view) {
        return new Animator[0];
    }

    private class AnimationInfo {
        public int position;
        public Animator animator;

        public AnimationInfo(int position, Animator animator) {
            this.position = position;
            this.animator = animator;
        }
    }
}