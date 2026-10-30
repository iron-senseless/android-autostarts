package com.elsdoerfer.android.autostarts.compat;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ExpandableListView;

import com.elsdoerfer.android.autostarts.R;


/**
 * To use fastScroll="true" with a ExpandableListView.
 */
public class FixedExpandableListView extends ExpandableListView {

	private final Rect mControlTouchBounds = new Rect();
	private View mControlTouchTarget;

	public FixedExpandableListView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public FixedExpandableListView(Context context) {
		super(context);
	}

	public FixedExpandableListView(Context context, AttributeSet attrs,
			int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Android's "android.widget.FastScroll" class has a bug when
	 * used with an ExpandableListView. When calculating the
	 * scroll target position, it mistakenly uses a flat index as
	 * a group position.
	 *
	 * Specifically, it calls this function with a packed group
	 * position that really is already the correct flat position.
	 *
	 * So we work around this by simply returning the incoming
	 * group position as the desired result. This only works of
	 * course as long as the FastScroll feature is the only thing
	 * using this method, which seems to be be the case.
	 *
	 * The result differs slightly from what FastScroll itself
	 * appears to attempt, namely instead of targeting only group
	 * items in the scroll, we target all items, which isn't as
	 * nice, but at least it works at all.
	 *
	 * It might also be possible to work around this by implementing
	 * a SectionIndex, but that is too much work right now.
	 *
	 * https://review.source.android.com/#change,22024
	 */
	@Override
	public int getFlatListPosition(long packedPosition) {
		return getPackedPositionGroup(packedPosition);
    }

	/**
	 * ExpandableListView handles child-row touches before descendants reliably
	 * receive them. Dispatch to interactive controls (including the receiver
	 * switches) before the list handles row clicks or scrolling.
	 *
	 * Ordinary touches outside these controls still go to ExpandableListView.
	 * This is also important near the fast-scroll gutter.
	 */
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			clearControlTouchTarget();
			mControlTouchTarget = findControlTouchTarget(event);
			if (mControlTouchTarget != null) {
				mControlTouchTarget.setPressed(true);
				return true;
			}
		}
		else if (mControlTouchTarget != null) {
			View target = mControlTouchTarget;
			if (action == MotionEvent.ACTION_UP) {
				boolean clicked = isInsideControl(target, event);
				clearControlTouchTarget();
				if (clicked)
					target.performClick();
			}
			else if (action == MotionEvent.ACTION_MOVE) {
				target.setPressed(isInsideControl(target, event));
			}
			else if (action == MotionEvent.ACTION_CANCEL
					|| action == MotionEvent.ACTION_POINTER_DOWN) {
				clearControlTouchTarget();
			}
			return true;
		}
		return super.dispatchTouchEvent(event);
	}

	private View findControlTouchTarget(MotionEvent event) {
		int position = pointToPosition((int) event.getX(), (int) event.getY());
		if (position == INVALID_POSITION)
			return null;

		View row = getChildAt(position - getFirstVisiblePosition());
		if (row == null)
			return null;

		// Receiver switches must win over the list's child-row click handler.
		View receiverSwitch = row.findViewById(R.id.receiver_switch);
		if (isInsideControl(receiverSwitch, event))
			return receiverSwitch;

		// Preserve the existing custom routing for event-info icons.
		View info = row.findViewById(R.id.show_info);
		return isInsideControl(info, event) ? info : null;
	}

	private boolean isInsideControl(View target, MotionEvent event) {
		return target != null
				&& target.getVisibility() == View.VISIBLE
				&& target.isEnabled()
				&& target.getGlobalVisibleRect(mControlTouchBounds)
				&& mControlTouchBounds.contains((int) event.getRawX(),
						(int) event.getRawY());
	}

	private void clearControlTouchTarget() {
		if (mControlTouchTarget != null) {
			mControlTouchTarget.setPressed(false);
			mControlTouchTarget = null;
		}
	}
}
