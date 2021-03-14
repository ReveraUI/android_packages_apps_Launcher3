/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.taskbar;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.launcher3.AbstractFloatingView.TYPE_ALL;
import static com.android.launcher3.AbstractFloatingView.TYPE_REPLACE_TASKBAR_WITH_HOTSEAT;
import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_BOTTOM_TAPPABLE_ELEMENT;
import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_EXTRA_NAVIGATION_BAR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseQuickstepLauncher;
import com.android.launcher3.Hotseat;
import com.android.launcher3.LauncherState;
import com.android.launcher3.QuickstepTransitionManager;
import com.android.launcher3.R;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.touch.ItemClickHandler;
import com.android.quickstep.AnimatedFloat;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Interfaces with Launcher/WindowManager/SystemUI to determine what to show in TaskbarView.
 */
public class TaskbarController {

    private static final String WINDOW_TITLE = "Taskbar";

    private final TaskbarContainerView mTaskbarContainerView;
    private final TaskbarView mTaskbarView;
    private final BaseQuickstepLauncher mLauncher;
    private final WindowManager mWindowManager;
    // Layout width and height of the Taskbar in the default state.
    private final Point mTaskbarSize;
    private final TaskbarStateHandler mTaskbarStateHandler;
    private final TaskbarVisibilityController mTaskbarVisibilityController;
    private final TaskbarHotseatController mHotseatController;
    private final TaskbarRecentsController mRecentsController;
    private final TaskbarDragController mDragController;

    // Initialized in init().
    private WindowManager.LayoutParams mWindowLayoutParams;

    // Contains all loaded Tasks, not yet deduped from Hotseat items.
    private List<Task> mLatestLoadedRecentTasks;
    // Contains all loaded Hotseat items.
    private ItemInfo[] mLatestLoadedHotseatItems;

    private @Nullable Animator mAnimator;
    private boolean mIsAnimatingToLauncher;

    public TaskbarController(BaseQuickstepLauncher launcher,
            TaskbarContainerView taskbarContainerView) {
        mLauncher = launcher;
        mTaskbarContainerView = taskbarContainerView;
        mTaskbarContainerView.construct(createTaskbarContainerViewCallbacks());
        mTaskbarView = mTaskbarContainerView.findViewById(R.id.taskbar_view);
        mTaskbarView.construct(createTaskbarViewCallbacks());
        mWindowManager = mLauncher.getWindowManager();
        mTaskbarSize = new Point(MATCH_PARENT, mLauncher.getDeviceProfile().taskbarSize);
        mTaskbarStateHandler = mLauncher.getTaskbarStateHandler();
        mTaskbarVisibilityController = new TaskbarVisibilityController(mLauncher,
                createTaskbarVisibilityControllerCallbacks());
        mHotseatController = new TaskbarHotseatController(mLauncher,
                createTaskbarHotseatControllerCallbacks());
        mRecentsController = new TaskbarRecentsController(mLauncher,
                createTaskbarRecentsControllerCallbacks());
        mDragController = new TaskbarDragController(mLauncher);
    }

    private TaskbarVisibilityControllerCallbacks createTaskbarVisibilityControllerCallbacks() {
        return new TaskbarVisibilityControllerCallbacks() {
            @Override
            public void updateTaskbarBackgroundAlpha(float alpha) {
                mTaskbarView.setBackgroundAlpha(alpha);
            }

            @Override
            public void updateTaskbarVisibilityAlpha(float alpha) {
                mTaskbarContainerView.setAlpha(alpha);
            }
        };
    }

    private TaskbarContainerViewCallbacks createTaskbarContainerViewCallbacks() {
        return new TaskbarContainerViewCallbacks() {
            @Override
            public void onViewRemoved() {
                if (mTaskbarContainerView.getChildCount() == 1) {
                    // Only TaskbarView remains.
                    setTaskbarWindowFullscreen(false);
                }
            }
        };
    }

    private TaskbarViewCallbacks createTaskbarViewCallbacks() {
        return new TaskbarViewCallbacks() {
            @Override
            public View.OnClickListener getItemOnClickListener() {
                return view -> {
                    Object tag = view.getTag();
                    if (tag instanceof Task) {
                        Task task = (Task) tag;
                        ActivityManagerWrapper.getInstance().startActivityFromRecents(task.key,
                                ActivityOptions.makeBasic());
                    } else if (tag instanceof FolderInfo) {
                        if (mLauncher.hasBeenResumed()) {
                            FolderInfo folderInfo = (FolderInfo) tag;
                            onClickedOnFolderFromHome(folderInfo);
                        } else {
                            FolderIcon folderIcon = (FolderIcon) view;
                            onClickedOnFolderInApp(folderIcon);
                        }
                    } else {
                        ItemClickHandler.INSTANCE.onClick(view);
                    }

                    AbstractFloatingView.closeAllOpenViews(
                            mTaskbarContainerView.getTaskbarActivityContext());
                };
            }

            // Open the real folder in Launcher.
            private void onClickedOnFolderFromHome(FolderInfo folderInfo) {
                alignRealHotseatWithTaskbar();

                FolderIcon folderIcon = (FolderIcon) mLauncher.getHotseat()
                        .getFirstItemMatch((info, v) -> info == folderInfo);
                folderIcon.post(folderIcon::performClick);
            }

            // Open the Taskbar folder, and handle clicks on folder items.
            private void onClickedOnFolderInApp(FolderIcon folderIcon) {
                Folder folder = folderIcon.getFolder();

                setTaskbarWindowFullscreen(true);

                mTaskbarContainerView.post(() -> {
                    folder.animateOpen();

                    folder.iterateOverItems((itemInfo, itemView) -> {
                        itemView.setOnClickListener(getItemOnClickListener());
                        itemView.setOnLongClickListener(getItemOnLongClickListener());
                        // To play haptic when dragging, like other Taskbar items do.
                        itemView.setHapticFeedbackEnabled(true);
                        return false;
                    });
                });
            }

            @Override
            public View.OnLongClickListener getItemOnLongClickListener() {
                return view -> {
                    if (mLauncher.hasBeenResumed() && view.getTag() instanceof ItemInfo) {
                        alignRealHotseatWithTaskbar();
                        return mDragController.startWorkspaceDragOnLongClick(view);
                    } else {
                        return mDragController.startSystemDragOnLongClick(view);
                    }
                };
            }

            @Override
            public int getEmptyHotseatViewVisibility() {
                // When on the home screen, we want the empty hotseat views to take up their full
                // space so that the others line up with the home screen hotseat.
                return mLauncher.hasBeenResumed() || mIsAnimatingToLauncher
                        ? View.INVISIBLE : View.GONE;
            }
        };
    }

    private TaskbarHotseatControllerCallbacks createTaskbarHotseatControllerCallbacks() {
        return new TaskbarHotseatControllerCallbacks() {
            @Override
            public void updateHotseatItems(ItemInfo[] hotseatItemInfos) {
                mTaskbarView.updateHotseatItems(hotseatItemInfos);
                mLatestLoadedHotseatItems = hotseatItemInfos;
                dedupeAndUpdateRecentItems();
            }
        };
    }

    private TaskbarRecentsControllerCallbacks createTaskbarRecentsControllerCallbacks() {
        return new TaskbarRecentsControllerCallbacks() {
            @Override
            public void updateRecentItems(ArrayList<Task> recentTasks) {
                mLatestLoadedRecentTasks = recentTasks;
                dedupeAndUpdateRecentItems();
            }

            @Override
            public void updateRecentTaskAtIndex(int taskIndex, Task task) {
                mTaskbarView.updateRecentTaskAtIndex(taskIndex, task);
            }
        };
    }

    /**
     * Initializes the Taskbar, including adding it to the screen.
     */
    public void init() {
        mTaskbarView.init(mHotseatController.getNumHotseatIcons(),
                mRecentsController.getNumRecentIcons());
        mTaskbarContainerView.init(mTaskbarView);
        addToWindowManager();
        mTaskbarStateHandler.setTaskbarCallbacks(createTaskbarStateHandlerCallbacks());
        mTaskbarVisibilityController.init();
        mHotseatController.init();
        mRecentsController.init();

        SCALE_PROPERTY.set(mTaskbarView, mLauncher.hasBeenResumed() ? getTaskbarScaleOnHome() : 1f);
    }

    private TaskbarStateHandlerCallbacks createTaskbarStateHandlerCallbacks() {
        return new TaskbarStateHandlerCallbacks() {
            @Override
            public AnimatedFloat getAlphaTarget() {
                return mTaskbarVisibilityController.getTaskbarVisibilityForLauncherState();
            }
        };
    }

    /**
     * Removes the Taskbar from the screen, and removes any obsolete listeners etc.
     */
    public void cleanup() {
        if (mAnimator != null) {
            // End this first, in case it relies on properties that are about to be cleaned up.
            mAnimator.end();
        }

        mTaskbarView.cleanup();
        mTaskbarContainerView.cleanup();
        removeFromWindowManager();
        mTaskbarStateHandler.setTaskbarCallbacks(null);
        mTaskbarVisibilityController.cleanup();
        mHotseatController.cleanup();
        mRecentsController.cleanup();
    }

    private void removeFromWindowManager() {
        mWindowManager.removeViewImmediate(mTaskbarContainerView);
    }

    private void addToWindowManager() {
        final int gravity = Gravity.BOTTOM;

        mWindowLayoutParams = new WindowManager.LayoutParams(
                mTaskbarSize.x,
                mTaskbarSize.y,
                TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle(WINDOW_TITLE);
        mWindowLayoutParams.packageName = mLauncher.getPackageName();
        mWindowLayoutParams.gravity = gravity;
        mWindowLayoutParams.setFitInsetsTypes(0);
        mWindowLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.setSystemApplicationOverlay(true);

        WindowManagerWrapper wmWrapper = WindowManagerWrapper.getInstance();
        wmWrapper.setProvidesInsetsTypes(
                mWindowLayoutParams,
                new int[] { ITYPE_EXTRA_NAVIGATION_BAR, ITYPE_BOTTOM_TAPPABLE_ELEMENT }
        );

        TaskbarContainerView.LayoutParams taskbarLayoutParams =
                new TaskbarContainerView.LayoutParams(mTaskbarSize.x, mTaskbarSize.y);
        taskbarLayoutParams.gravity = gravity;
        mTaskbarView.setLayoutParams(taskbarLayoutParams);

        mWindowManager.addView(mTaskbarContainerView, mWindowLayoutParams);
    }

    /**
     * Should be called from onResume() and onPause(), and animates the Taskbar accordingly.
     */
    public void onLauncherResumedOrPaused(boolean isResumed) {
        long duration = QuickstepTransitionManager.CONTENT_ALPHA_DURATION;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        if (isResumed) {
            mAnimator = createAnimToLauncher(null, duration);
        } else {
            mAnimator = createAnimToApp(duration);
            replaceTaskbarWithHotseatOrViceVersa();
        }
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
            }
        });
        mAnimator.start();
    }

    /**
     * Create Taskbar animation when going from an app to Launcher.
     * @param toState If known, the state we will end up in when reaching Launcher.
     */
    public Animator createAnimToLauncher(@Nullable LauncherState toState, long duration) {
        PendingAnimation anim = new PendingAnimation(duration);
        anim.add(mTaskbarVisibilityController.createAnimToBackgroundAlpha(0, duration));
        if (toState != null) {
            mTaskbarStateHandler.setStateWithAnimation(toState, new StateAnimationConfig(), anim);
        }
        anim.addFloat(mTaskbarView, SCALE_PROPERTY, mTaskbarView.getScaleX(),
                getTaskbarScaleOnHome(), LINEAR);

        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mIsAnimatingToLauncher = true;
                mTaskbarView.updateHotseatItemsVisibility();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mIsAnimatingToLauncher = false;
            }
        });

        anim.addOnFrameCallback(this::alignRealHotseatWithTaskbar);

        return anim.buildAnim();
    }

    private Animator createAnimToApp(long duration) {
        PendingAnimation anim = new PendingAnimation(duration);
        anim.add(mTaskbarVisibilityController.createAnimToBackgroundAlpha(1, duration));
        anim.addFloat(mTaskbarView, SCALE_PROPERTY, mTaskbarView.getScaleX(), 1f, LINEAR);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mTaskbarView.updateHotseatItemsVisibility();
                setReplaceTaskbarWithHotseat(false);
            }
        });
        return anim.buildAnim();
    }

    /**
     * Should be called when the IME visibility changes, so we can hide/show Taskbar accordingly.
     */
    public void setIsImeVisible(boolean isImeVisible) {
        mTaskbarVisibilityController.animateToVisibilityForIme(isImeVisible ? 0 : 1);
    }

    /**
     * Should be called when one or more items in the Hotseat have changed.
     */
    public void onHotseatUpdated() {
        mHotseatController.onHotseatUpdated();
    }

    /**
     * @param ev MotionEvent in screen coordinates.
     * @return Whether any Taskbar item could handle the given MotionEvent if given the chance.
     */
    public boolean isEventOverAnyTaskbarItem(MotionEvent ev) {
        return mTaskbarView.isEventOverAnyItem(ev);
    }

    public boolean isDraggingItem() {
        return mTaskbarView.isDraggingItem();
    }

    private void dedupeAndUpdateRecentItems() {
        if (mLatestLoadedRecentTasks == null || mLatestLoadedHotseatItems == null) {
            return;
        }

        final int numRecentIcons = mRecentsController.getNumRecentIcons();

        // From most recent to least recently opened.
        List<Task> dedupedTasksInDescendingOrder = new ArrayList<>();
        for (int i = mLatestLoadedRecentTasks.size() - 1; i >= 0; i--) {
            Task task = mLatestLoadedRecentTasks.get(i);
            boolean isTaskInHotseat = false;
            for (ItemInfo hotseatItem : mLatestLoadedHotseatItems) {
                if (hotseatItem == null) {
                    continue;
                }
                ComponentName hotseatActivity = hotseatItem.getTargetComponent();
                if (hotseatActivity != null && task.key.sourceComponent.getPackageName()
                        .equals(hotseatActivity.getPackageName())) {
                    isTaskInHotseat = true;
                    break;
                }
            }
            if (!isTaskInHotseat) {
                dedupedTasksInDescendingOrder.add(task);
                if (dedupedTasksInDescendingOrder.size() == numRecentIcons) {
                    break;
                }
            }
        }

        // TaskbarView expects an array of all the recent tasks to show, in the order to show them.
        // So we create an array of the proper size, then fill it in such that the most recent items
        // are at the end. If there aren't enough elements to fill the array, leave them null.
        Task[] tasksArray = new Task[numRecentIcons];
        for (int i = 0; i < tasksArray.length; i++) {
            Task task = i >= dedupedTasksInDescendingOrder.size()
                    ? null
                    : dedupedTasksInDescendingOrder.get(i);
            tasksArray[tasksArray.length - 1 - i] = task;
        }

        mTaskbarView.updateRecentTasks(tasksArray);
        mRecentsController.loadIconsForTasks(tasksArray);
    }

    /**
     * @return Whether the given View is in the same window as Taskbar.
     */
    public boolean isViewInTaskbar(View v) {
        return mTaskbarContainerView.isAttachedToWindow()
                && mTaskbarContainerView.getWindowId().equals(v.getWindowId());
    }

    /**
     * Pads the Hotseat to line up exactly with Taskbar's copy of the Hotseat.
     */
    public void alignRealHotseatWithTaskbar() {
        Rect hotseatBounds = new Rect();
        mTaskbarView.getHotseatBoundsAtScale(getTaskbarScaleOnHome()).roundOut(hotseatBounds);
        mLauncher.getHotseat().setPadding(hotseatBounds.left, hotseatBounds.top,
                mTaskbarView.getWidth() - hotseatBounds.right,
                mTaskbarView.getHeight() - hotseatBounds.bottom);
    }

    /**
     * A view was added or removed from DragLayer, check if we need to hide our hotseat copy and
     * show the real one instead.
     */
    public void onLauncherDragLayerHierarchyChanged() {
        replaceTaskbarWithHotseatOrViceVersa();
    }

    private void replaceTaskbarWithHotseatOrViceVersa() {
        boolean replaceTaskbarWithHotseat = AbstractFloatingView.getTopOpenViewWithType(mLauncher,
                TYPE_ALL & TYPE_REPLACE_TASKBAR_WITH_HOTSEAT) != null;
        if (!mLauncher.hasBeenResumed()) {
            replaceTaskbarWithHotseat = false;
        }
        setReplaceTaskbarWithHotseat(replaceTaskbarWithHotseat);
    }

    private void setReplaceTaskbarWithHotseat(boolean replaceTaskbarWithHotseat) {
        Hotseat hotseat = mLauncher.getHotseat();
        if (replaceTaskbarWithHotseat) {
            alignRealHotseatWithTaskbar();
            hotseat.getReplaceTaskbarAlpha().setValue(1f);
            mTaskbarView.setHotseatViewsHidden(true);
        } else {
            hotseat.getReplaceTaskbarAlpha().setValue(0f);
            mTaskbarView.setHotseatViewsHidden(false);
        }
    }

    private float getTaskbarScaleOnHome() {
        return 1f / mTaskbarContainerView.getTaskbarActivityContext().getTaskbarIconScale();
    }

    /**
     * Updates the TaskbarContainer to MATCH_PARENT vs original Taskbar size.
     */
    private void setTaskbarWindowFullscreen(boolean fullscreen) {
        if (fullscreen) {
            mWindowLayoutParams.width = MATCH_PARENT;
            mWindowLayoutParams.height = MATCH_PARENT;
        } else {
            mWindowLayoutParams.width = mTaskbarSize.x;
            mWindowLayoutParams.height = mTaskbarSize.y;
        }
        mWindowManager.updateViewLayout(mTaskbarContainerView, mWindowLayoutParams);
    }

    /**
     * Contains methods that TaskbarStateHandler can call to interface with TaskbarController.
     */
    protected interface TaskbarStateHandlerCallbacks {
        AnimatedFloat getAlphaTarget();
    }

    /**
     * Contains methods that TaskbarVisibilityController can call to interface with
     * TaskbarController.
     */
    protected interface TaskbarVisibilityControllerCallbacks {
        void updateTaskbarBackgroundAlpha(float alpha);
        void updateTaskbarVisibilityAlpha(float alpha);
    }

    /**
     * Contains methods that TaskbarContainerView can call to interface with TaskbarController.
     */
    protected interface TaskbarContainerViewCallbacks {
        void onViewRemoved();
    }

    /**
     * Contains methods that TaskbarView can call to interface with TaskbarController.
     */
    protected interface TaskbarViewCallbacks {
        View.OnClickListener getItemOnClickListener();
        View.OnLongClickListener getItemOnLongClickListener();
        int getEmptyHotseatViewVisibility();
    }

    /**
     * Contains methods that TaskbarHotseatController can call to interface with TaskbarController.
     */
    protected interface TaskbarHotseatControllerCallbacks {
        void updateHotseatItems(ItemInfo[] hotseatItemInfos);
    }

    /**
     * Contains methods that TaskbarRecentsController can call to interface with TaskbarController.
     */
    protected interface TaskbarRecentsControllerCallbacks {
        void updateRecentItems(ArrayList<Task> recentTasks);
        void updateRecentTaskAtIndex(int taskIndex, Task task);
    }
}
