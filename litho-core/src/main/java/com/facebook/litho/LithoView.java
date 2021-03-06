/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho;

import static android.content.Context.ACCESSIBILITY_SERVICE;
import static com.facebook.litho.AccessibilityUtils.isAccessibilityEnabled;
import static com.facebook.litho.ThreadUtils.assertMainThread;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.support.annotation.VisibleForTesting;
import android.support.v4.view.accessibility.AccessibilityManagerCompat;
import android.support.v4.view.accessibility.AccessibilityManagerCompat.AccessibilityStateChangeListenerCompat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.proguard.annotations.DoNotStrip;
import java.lang.ref.WeakReference;
import java.util.Deque;
import javax.annotation.Nullable;

/**
 * A {@link ViewGroup} that can host the mounted state of a {@link Component}.
 */
public class LithoView extends ComponentHost {

  public interface OnDirtyMountListener {
    /**
     * Called when finishing a mount where the mount state was dirty. This indicates that there were
     * new props/state in the tree, or the LithoView was mounting a new ComponentTree
     */
    void onDirtyMount(LithoView view);
  }

  private ComponentTree mComponentTree;
  private final MountState mMountState;
  private boolean mIsAttached;
  // The bounds of the visible rect that was used for the previous incremental mount.
  private final Rect mPreviousMountVisibleRectBounds = new Rect();
  // The view bounds when the previous incremental mount occurred.
  private final Rect mPreviousMountViewBounds = new Rect();

  private boolean mForceLayout;
  private boolean mSuppressMeasureComponentTree;
  private boolean mIsMeasuring = false;
  private boolean mHasNewComponentTree = false;
  private int mAnimatedWidth = -1;
  private int mAnimatedHeight = -1;
  private OnDirtyMountListener mOnDirtyMountListener = null;

  private final AccessibilityManager mAccessibilityManager;

  private final AccessibilityStateChangeListener mAccessibilityStateChangeListener =
      new AccessibilityStateChangeListener(this);

  private static final int[] sLayoutSize = new int[2];

  // Keep ComponentTree when detached from this view in case the ComponentTree is shared between
  // sticky header and RecyclerView's binder
  // TODO T14859077 Replace with proper solution
  private ComponentTree mTemporaryDetachedComponent;
  private int mTransientStateCount;
  private boolean mDoesOwnIncrementalMount;
  private boolean mDoMeasureInLayout;

  /**
   * Create a new {@link LithoView} instance and initialize it
   * with the given {@link Component} root.
   *
   * @param context Android {@link Context}.
   * @param component The root component to draw.
   * @return {@link LithoView} able to render a {@link Component} hierarchy.
   */
  public static LithoView create(Context context, Component component) {
    return create(new ComponentContext(context), component);
  }

  /**
   * Create a new {@link LithoView} instance and initialize it
   * with the given {@link Component} root.
   *
   * @param context {@link ComponentContext}.
   * @param component The root component to draw.
   * @return {@link LithoView} able to render a {@link Component} hierarchy.
   */
  public static LithoView create(ComponentContext context, Component component) {
    final LithoView lithoView = new LithoView(context);
    lithoView.setComponentTree(ComponentTree.create(context, component).build());

    return lithoView;
  }

  public LithoView(Context context) {
    this(context, null);
  }

  public LithoView(Context context, AttributeSet attrs) {
    this(new ComponentContext(context), attrs);
  }

  public LithoView(ComponentContext context) {
    this(context, null);
  }

  public LithoView(ComponentContext context, AttributeSet attrs) {
    super(context, attrs);

    mMountState = new MountState(this);
    mAccessibilityManager = (AccessibilityManager) context.getSystemService(ACCESSIBILITY_SERVICE);
  }

  private static void performLayoutOnChildrenIfNecessary(ComponentHost host) {
    for (int i = 0, count = host.getChildCount(); i < count; i++) {
      final View child = host.getChildAt(i);

      if (child.isLayoutRequested()) {
        // The hosting view doesn't allow children to change sizes dynamically as
        // this would conflict with the component's own layout calculations.
        child.measure(
            MeasureSpec.makeMeasureSpec(child.getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(child.getHeight(), MeasureSpec.EXACTLY));
        child.layout(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
      }

      if (child instanceof ComponentHost) {
        performLayoutOnChildrenIfNecessary((ComponentHost) child);
      }
    }
  }

  protected void forceRelayout() {
    mForceLayout = true;
    requestLayout();
  }

  public void startTemporaryDetach() {
    mTemporaryDetachedComponent = mComponentTree;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    onAttach();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    onDetach();
  }

  @Override
  public void onStartTemporaryDetach() {
    super.onStartTemporaryDetach();
    onDetach();
  }

  @Override
  public void onFinishTemporaryDetach() {
    super.onFinishTemporaryDetach();
    onAttach();
  }

  private void onAttach() {
    if (!mIsAttached) {
      mIsAttached = true;

      if (mComponentTree != null) {
        mComponentTree.attach();
      }

      refreshAccessibilityDelegatesIfNeeded(isAccessibilityEnabled(getContext()));

      AccessibilityManagerCompat.addAccessibilityStateChangeListener(
          mAccessibilityManager,
          mAccessibilityStateChangeListener);
    }
  }

  private void onDetach() {
    if (mIsAttached) {
      mIsAttached = false;
      mMountState.detach();

      if (mComponentTree != null) {
        mComponentTree.detach();
      }

      AccessibilityManagerCompat.removeAccessibilityStateChangeListener(
          mAccessibilityManager,
          mAccessibilityStateChangeListener);

      mSuppressMeasureComponentTree = false;
    }
  }

  /**
   * If set to true, the onMeasure(..) call won't measure the ComponentTree with the given
   * measure specs, but it will just use them as measured dimensions.
   */
  public void suppressMeasureComponentTree(boolean suppress) {
    mSuppressMeasureComponentTree = suppress;
  }

  /**
   * Sets the width that the LithoView should take on the next measure pass and then requests a
   * layout. This should be called from animation-driving code on each frame to animate the size of
   * the LithoView.
   */
  public void setAnimatedWidth(int width) {
    mAnimatedWidth = width;
    requestLayout();
  }

  /**
   * Sets the height that the LithoView should take on the next measure pass and then requests a
   * layout. This should be called from animation-driving code on each frame to animate the size of
   * the LithoView.
   */
  public void setAnimatedHeight(int height) {
    mAnimatedHeight = height;
    requestLayout();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    if (ComponentsConfiguration.doubleMeasureCorrection) {
      widthMeasureSpec = correctWidthSpecForAndroidDoubleMeasureBug(widthMeasureSpec);
    }

    // mAnimatedWidth/mAnimatedHeight >= 0 if something is driving a width/height animation.
    final boolean animating = mAnimatedWidth != -1 || mAnimatedHeight != -1;
    // up to date view sizes, taking into account running animations
    final int upToDateWidth = (mAnimatedWidth != -1) ? mAnimatedWidth : getWidth();
    final int upToDateHeight = (mAnimatedHeight != -1) ? mAnimatedHeight : getHeight();
    mAnimatedWidth = -1;
    mAnimatedHeight = -1;

    if (animating) {
      // If the mount state is dirty, we want to ignore the current animation and calculate the
      // new LayoutState as normal below. That LayoutState has the opportunity to define its own
      // transition to a new width/height from the current height of the LithoView, or if not we
      // will jump straight to that width/height.
      if (!isMountStateDirty()) {
        setMeasuredDimension(upToDateWidth, upToDateHeight);
        return;
      }
    }

    LayoutParams layoutParams = getLayoutParams();
    if (layoutParams instanceof LayoutManagerOverrideParams) {
      LayoutManagerOverrideParams layoutManagerOverrideParams =
          (LayoutManagerOverrideParams) layoutParams;
      widthMeasureSpec = layoutManagerOverrideParams.getWidthMeasureSpec();
      heightMeasureSpec = layoutManagerOverrideParams.getHeightMeasureSpec();
    }

    int width = MeasureSpec.getSize(widthMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);

    if (mTemporaryDetachedComponent != null && mComponentTree == null) {
      setComponentTree(mTemporaryDetachedComponent);
      mTemporaryDetachedComponent = null;
    }

    if (ComponentsConfiguration.lazyLayoutForExactSpec) {
      if (!mForceLayout
          && SizeSpec.getMode(widthMeasureSpec) == SizeSpec.EXACTLY
          && SizeSpec.getMode(heightMeasureSpec) == SizeSpec.EXACTLY) {
        mDoMeasureInLayout = true;
        setMeasuredDimension(width, height);
        return;
      }
    }

    mIsMeasuring = true;

    if (mComponentTree != null && !mSuppressMeasureComponentTree) {
      boolean forceRelayout = mForceLayout;
      mForceLayout = false;
      mComponentTree.measure(widthMeasureSpec, heightMeasureSpec, sLayoutSize, forceRelayout);

      width = sLayoutSize[0];
      height = sLayoutSize[1];
      mDoMeasureInLayout = false;
    }

    final boolean canAnimateRootBounds =
        !mSuppressMeasureComponentTree
            && mComponentTree != null
            && (!mHasNewComponentTree || !mComponentTree.hasMounted());

    if (canAnimateRootBounds) {
      // We might need to collect transitions before mount to know whether this LithoView has
      // width or height animation.
      mComponentTree.maybeCollectTransitions();

      final int initialAnimatedWidth =
          mComponentTree.getInitialAnimatedLithoViewWidth(upToDateWidth, mHasNewComponentTree);
      if (initialAnimatedWidth != -1) {
        width = initialAnimatedWidth;
      }

      final int initialAnimatedHeight =
          mComponentTree.getInitialAnimatedLithoViewHeight(upToDateHeight, mHasNewComponentTree);
      if (initialAnimatedHeight != -1) {
        height = initialAnimatedHeight;
      }
    }
    setMeasuredDimension(width, height);

    mHasNewComponentTree = false;
    mIsMeasuring = false;
  }

  /**
   * Correction for an Android bug on some devices with "special" densities where the system will
   * double-measure with slightly different widths in the same traversal. See
   * https://issuetracker.google.com/issues/73804230 for more details.
   *
   * <p>This hits Litho extra-hard because we create {@link LayoutState}s in measure in many places,
   * so we try to correct for it here by replacing the widthSpec given to us from above with what we
   * think the correct one is. Even though the double measure will still happen, the incorrect width
   * will not propagate to any vertical RecyclerViews contained within.
   */
  private int correctWidthSpecForAndroidDoubleMeasureBug(int widthSpec) {
    final @SizeSpec.MeasureSpecMode int mode = SizeSpec.getMode(widthSpec);
    if (mode == SizeSpec.UNSPECIFIED) {
      return widthSpec;
    }

    final Resources resources = getResources();
    final Configuration configuration = resources.getConfiguration();
    final DisplayMetrics displayMetrics = resources.getDisplayMetrics();
    final int screenWidthPx = displayMetrics.widthPixels;
    final float screenDensity = displayMetrics.density;

    // NB: Logic taken from ViewRootImpl#dipToPx
    final int calculatedScreenWidthPx = (int) (screenDensity * configuration.screenWidthDp + 0.5f);

    if (screenWidthPx != calculatedScreenWidthPx
        && calculatedScreenWidthPx == SizeSpec.getSize(widthSpec)) {
      return SizeSpec.makeSizeSpec(screenWidthPx, mode);
    }

    return widthSpec;
  }

  @Override
  protected void performLayout(boolean changed, int left, int top, int right, int bottom) {

    if (mComponentTree != null) {
      if (mComponentTree.isReleased()) {
        throw new IllegalStateException(
            "Trying to layout a LithoView holding onto a released ComponentTree");
      }

      if (mDoMeasureInLayout || mComponentTree.getMainThreadLayoutState() == null) {
        // Call measure so that we get a layout state that we can use for layout.
        mComponentTree.measure(
            MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY),
            sLayoutSize,
            false);
        mHasNewComponentTree = false;
        mDoMeasureInLayout = false;
      }

      boolean wasMountTriggered = mComponentTree.layout();

      final boolean isRectSame =
          mPreviousMountViewBounds != null
              && mPreviousMountViewBounds.left == left
              && mPreviousMountViewBounds.top == top
              && mPreviousMountViewBounds.right == right
              && mPreviousMountViewBounds.bottom == bottom;

      // If this happens the LithoView might have moved on Screen without a scroll event
      // triggering incremental mount. We trigger one here to be sure all the content is visible.
      if (!wasMountTriggered
          && !isRectSame
          && isIncrementalMountEnabled()) {
        performIncrementalMount();
      }

      if (!wasMountTriggered || shouldAlwaysLayoutChildren()) {
        // If the layout() call on the component didn't trigger a mount step,
        // we might need to perform an inner layout traversal on children that
        // requested it as certain complex child views (e.g. ViewPager,
        // RecyclerView, etc) rely on that.
        performLayoutOnChildrenIfNecessary(this);
      }
    }
  }

  /**
   * Indicates if the children of this view should be laid regardless to a mount step being
   * triggered on layout. This step can be important when some of the children in the hierarchy
   * are changed (e.g. resized) but the parent wasn't.
   *
   * Since the framework doesn't expect its children to resize after being mounted, this should be
   * used only for extreme cases where the underline views are complex and need this behavior.
   *
   * @return boolean Returns true if the children of this view should be laid out even when a mount
   *    step was not needed.
   */
  protected boolean shouldAlwaysLayoutChildren() {
    return false;
  }

  /**
   * @return {@link ComponentContext} associated with this LithoView. It's a wrapper on the
   * {@link Context} originally used to create this LithoView itself.
   */
  public ComponentContext getComponentContext() {
    return (ComponentContext) getContext();
  }

  @Override
  protected boolean shouldRequestLayout() {
    // Don't bubble up layout requests while mounting.
    if (mComponentTree != null && mComponentTree.isMounting()) {
      return false;
    }

    return super.shouldRequestLayout();
  }

  void assertNotInMeasure() {
    if (mIsMeasuring) {
      // If the ComponentTree is updated during measure, the following .layout() call will not run
      // on the ComponentTree that was prepared in measure.
      throw new RuntimeException("Cannot update ComponentTree while in the middle of measure");
    }
  }

  @Nullable
  public ComponentTree getComponentTree() {
    return mComponentTree;
  }

  public void setOnDirtyMountListener(OnDirtyMountListener onDirtyMountListener) {
    mOnDirtyMountListener = onDirtyMountListener;
  }

  void onDirtyMountComplete() {
    if (mOnDirtyMountListener != null) {
      mOnDirtyMountListener.onDirtyMount(this);
    }
  }

  public void setComponentTree(ComponentTree componentTree) {
    assertMainThread();
    assertNotInMeasure();

    mTemporaryDetachedComponent = null;
    if (mComponentTree == componentTree) {
      if (mIsAttached) {
        rebind();
      }
      return;
    }

    mHasNewComponentTree =
        mComponentTree == null || componentTree == null || mComponentTree.mId != componentTree.mId;
    setMountStateDirty();

    if (mComponentTree != null) {
      if (ComponentsConfiguration.unmountAllWhenComponentTreeSetToNull && componentTree == null) {
        unmountAllItems();
      }

      if (mIsAttached) {
        mComponentTree.detach();
      }

      mComponentTree.clearLithoView();
    }

    mComponentTree = componentTree;

    if (mComponentTree != null) {
      if (mComponentTree.isReleased()) {
        throw new IllegalStateException(
            "Setting a released ComponentTree to a LithoView, "
                + "released component was: "
                + mComponentTree.getReleasedComponent());
      }
      mComponentTree.setLithoView(this);

      if (mIsAttached) {
        mComponentTree.attach();
      } else {
        requestLayout();
      }
    }
  }

  /**
   * Change the root component synchronously.
   */
  public void setComponent(Component component) {
    if (mComponentTree == null) {
      setComponentTree(ComponentTree.create(getComponentContext(), component).build());
    } else {
      mComponentTree.setRoot(component);
    }
  }

  /**
   * Change the root component measuring it on a background thread before updating the UI.
   * If this {@link LithoView} doesn't have a ComponentTree initialized, the root will be
   * computed synchronously.
   */
  public void setComponentAsync(Component component) {
    if (mComponentTree == null) {
      setComponentTree(ComponentTree.create(getComponentContext(), component).build());
    } else {
      mComponentTree.setRootAsync(component);
    }
  }

  public void rebind() {
    mMountState.rebind();
  }

  /**
   * To be called this when the LithoView is about to become inactive. This means that either
   * the view is about to be recycled or moved off-screen.
   */
  public void unbind() {
    mMountState.unbind();
  }

  /**
   * Called from the ComponentTree when a new view want to use the same ComponentTree.
   */
  void clearComponentTree() {
    assertMainThread();

    if (mIsAttached) {
      throw new IllegalStateException("Trying to clear the ComponentTree while attached.");
    }

    mComponentTree = null;
  }

  @Override
  public void setHasTransientState(boolean hasTransientState) {
    if (hasTransientState) {
      if (mTransientStateCount == 0 && isIncrementalMountEnabled()) {
        final Rect rect = ComponentsPools.acquireRect();
        rect.set(0, 0, getWidth(), getHeight());
        performIncrementalMount(rect, false);
        ComponentsPools.release(rect);
      }
      mTransientStateCount++;
    } else {
      mTransientStateCount--;
      if (mTransientStateCount == 0 && isIncrementalMountEnabled()) {
        // We mounted everything when the transient state was set on this view. We need to do this
        // partly to unmount content that is not visible but mostly to get the correct visibility
        // events to be fired.
        performIncrementalMount();
      }
      if (mTransientStateCount < 0) {
        mTransientStateCount = 0;
      }
    }

    super.setHasTransientState(hasTransientState);
  }

  @Override
  public void offsetTopAndBottom(int offset) {
    super.offsetTopAndBottom(offset);

    maybePerformIncrementalMountOnView();
  }

  @Override
  public void offsetLeftAndRight(int offset) {
    super.offsetLeftAndRight(offset);

    maybePerformIncrementalMountOnView();
  }

  @Override
  public void setTranslationX(float translationX) {
    if (translationX == getTranslationX()) {
      return;
    }
    super.setTranslationX(translationX);

    maybePerformIncrementalMountOnView();
  }

  @Override
  public void setTranslationY(float translationY) {
    if (translationY == getTranslationY()) {
      return;
    }
    super.setTranslationY(translationY);

    maybePerformIncrementalMountOnView();
  }

  private void maybePerformIncrementalMountOnView() {
    if (!isIncrementalMountEnabled() || !(getParent() instanceof View)) {
      return;
    }

    int parentWidth = ((View) getParent()).getWidth();
    int parentHeight = ((View) getParent()).getHeight();

    final int translationX = (int) getTranslationX();
    final int translationY = (int) getTranslationY();
    final int top = getTop() + translationY;
    final int bottom = getBottom() + translationY;
    final int left = getLeft() + translationX;
    final int right = getRight() + translationX;

    if (left >= 0
        && top >= 0
        && right <= parentWidth
        && bottom <= parentHeight
        && mPreviousMountVisibleRectBounds.width() == getWidth()
        && mPreviousMountVisibleRectBounds.height() == getHeight()) {
      // View is fully visible, and has already been completely mounted.
      return;
    }

    final Rect rect = ComponentsPools.acquireRect();
    final boolean isEmpty;
    if ((mComponentTree != null && mComponentTree.useExactRectForVisibilityEvents())
        || ComponentsConfiguration.lithoViewIncrementalMountUsesLocalVisibleBounds) {
      isEmpty = !getLocalVisibleRect(rect);
    } else {
      rect.set(
          Math.max(0, -left),
          Math.max(0, -top),
          Math.min(right, parentWidth) - left,
          Math.min(bottom, parentHeight) - top);
      isEmpty = rect.isEmpty();
    }

    if (isEmpty) {
      // View is not visible at all, nothing to do.
      ComponentsPools.release(rect);
      return;
    }

    performIncrementalMount(rect, true);

    ComponentsPools.release(rect);
  }

  /**
   * Checks to make sure the main thread layout state is valid (and throws if it's both invalid and
   * there's no layout requested to make it valid).
   * @return whether the main thread layout state is ok to use
   */
  private boolean checkMainThreadLayoutStateForIncrementalMount() {
    if (mComponentTree.getMainThreadLayoutState() != null) {
      return true;
    }

    if (!isLayoutRequested()) {
      throw new RuntimeException(
          "Trying to incrementally mount a component with a null main thread LayoutState on a " +
              "LithoView that hasn't requested layout!");
    }

    return false;
  }

  public void performIncrementalMount(Rect visibleRect, boolean processVisibilityOutputs) {
    if (mComponentTree == null || !checkMainThreadLayoutStateForIncrementalMount()) {
      return;
    }

    if (mComponentTree.isIncrementalMountEnabled()) {
      mComponentTree.mountComponent(visibleRect, processVisibilityOutputs);
    } else {
      throw new IllegalStateException("To perform incremental mounting, you need first to enable" +
          " it when creating the ComponentTree.");
    }
  }

  public void performIncrementalMount() {
    if (mComponentTree == null || mComponentTree.getMainThreadLayoutState() == null) {
      return;
    }

    if (mComponentTree.isIncrementalMountEnabled()) {
      mComponentTree.incrementalMountComponent();
    } else {
      throw new IllegalStateException("To perform incremental mounting, you need first to enable" +
          " it when creating the ComponentTree.");
    }
  }

  public boolean isIncrementalMountEnabled() {
    return (mComponentTree != null && mComponentTree.isIncrementalMountEnabled());
  }

  public void release() {
    assertMainThread();

    if (mComponentTree != null) {
      mComponentTree.release();
      mComponentTree = null;
    }
  }

  void mount(LayoutState layoutState, Rect currentVisibleArea, boolean processVisibilityOutputs) {
    boolean rectNeedsRelease = false;
    if (mTransientStateCount > 0 && isIncrementalMountEnabled()) {
      // If transient state is set but the MountState is dirty we want to re-mount everything.
      // Otherwise, we don't need to do anything as the entire LithoView was mounted when the
      // transient state was set.
      if (!mMountState.isDirty()) {
        return;
      } else {
        currentVisibleArea = ComponentsPools.acquireRect();
        currentVisibleArea.set(0, 0, getWidth(), getHeight());
        rectNeedsRelease = true;
        processVisibilityOutputs = false;
      }
    }

    if (currentVisibleArea == null) {
      mPreviousMountVisibleRectBounds.setEmpty();
    } else {
      mPreviousMountVisibleRectBounds.set(currentVisibleArea);
    }

    mPreviousMountViewBounds.set(getLeft(), getTop(), getRight(), getBottom());

    mMountState.mount(layoutState, currentVisibleArea, processVisibilityOutputs);

    if (rectNeedsRelease) {
      ComponentsPools.release(currentVisibleArea);
    }
  }

  public void unmountAllItems() {
    mMountState.unmountAllItems();
    mPreviousMountVisibleRectBounds.setEmpty();
    mPreviousMountViewBounds.setEmpty();
  }

  public Rect getPreviousMountBounds() {
    return mPreviousMountVisibleRectBounds;
  }

  void setMountStateDirty() {
    mMountState.setDirty();
    mPreviousMountVisibleRectBounds.setEmpty();
    mPreviousMountViewBounds.setEmpty();
  }

  boolean isMountStateDirty() {
    return mMountState.isDirty();
  }

  boolean mountStateNeedsRemount() {
    return mMountState.needsRemount();
  }

  MountState getMountState() {
    return mMountState;
  }

  boolean doesOwnIncrementalMount() {
    return mDoesOwnIncrementalMount;
  }

  @Deprecated
  /**
   * @deprecated This is being temporarily added while we experiment with other solutions for
   *     incremental mount (see {@link
   *     ComponentsConfiguration#incrementalMountUsesLocalVisibleBounds} and {@link
   *     ComponentsConfiguration#lithoViewIncrementalMountUsesLocalVisibleBounds}.
   */
  public void setDoesOwnIncrementalMount(boolean doesOwnIncrementalMount) {
    mDoesOwnIncrementalMount = doesOwnIncrementalMount;

    setDoesOwnIncrementalMountOnChildren(this, doesOwnIncrementalMount);
  }

  private void setDoesOwnIncrementalMountOnChildren(
      ViewGroup viewGroup, boolean doesOwnIncrementalMount) {
    for (int i = 0, size = viewGroup.getChildCount(); i < size; i++) {
      final View child = viewGroup.getChildAt(i);

      if (child instanceof LithoView) {
        ((LithoView) child).setDoesOwnIncrementalMount(doesOwnIncrementalMount);
      }

      if (child instanceof ViewGroup) {
        setDoesOwnIncrementalMountOnChildren((ViewGroup) child, doesOwnIncrementalMount);
      }
    }
  }

  @DoNotStrip
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Deque<TestItem> findTestItems(String testKey) {
    return mMountState.findTestItems(testKey);
  }

  private static class AccessibilityStateChangeListener extends
      AccessibilityStateChangeListenerCompat {
    private final WeakReference<LithoView> mLithoView;

    private AccessibilityStateChangeListener(LithoView lithoView) {
      mLithoView = new WeakReference<>(lithoView);
    }

    @Override
    public void onAccessibilityStateChanged(boolean enabled) {
      final LithoView lithoView = mLithoView.get();
      if (lithoView == null) {
        return;
      }

      lithoView.refreshAccessibilityDelegatesIfNeeded(enabled);

      lithoView.requestLayout();
    }
  }

  /**
   * LayoutParams that override the LayoutManager.
   *
   * <p>If you set LayoutParams on a LithoView that implements this interface, the view will
   * completely ignore the layout specs given to it by its LayoutManager and use these specs
   * instead. To use, set the LayoutParams height and width to {@link
   * ViewGroup.LayoutParams#WRAP_CONTENT} and then provide a width and height measure spec though
   * this interface.
   *
   * <p>This is helpful for implementing {@link View.MeasureSpec#AT_MOST} support since Android
   * LayoutManagers don't support an AT_MOST concept as part of {@link ViewGroup.LayoutParams}'s
   * special values.
   */
  public interface LayoutManagerOverrideParams {
    int getWidthMeasureSpec();

    int getHeightMeasureSpec();
  }

  @Override
  public String toString() {
    return LithoViewTestHelper.viewToString(this, true);
  }
}
