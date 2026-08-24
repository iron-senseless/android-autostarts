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

	private final Rect mInfoTouchBounds = new Rect();
	private View mInfoTouchTarget;

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
	 * ExpandableListView handles a touch on a child row itself, before a
	 * clickable descendant can reliably receive it. This is especially
	 * noticeable when the row has an info affordance near the fast-scroll
	 * gutter: the touch either expands the group or opens the event dialog.
	 *
	 * Route touches inside a visible event-info target directly to that target
	 * before the list's row and fast-scroll handlers see them.
	 */
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			clearInfoTouchTarget();
			mInfoTouchTarget = findInfoTouchTarget(event);
			if (mInfoTouchTarget != null) {
				mInfoTouchTarget.setPressed(true);
				return true;
			}
		}
		else if (mInfoTouchTarget != null) {
			View target = mInfoTouchTarget;
			if (action == MotionEvent.ACTION_UP) {
				boolean clicked = isInsideInfoTarget(target, event);
				clearInfoTouchTarget();
				if (clicked)
					target.performClick();
			}
			else if (action == MotionEvent.ACTION_MOVE) {
				target.setPressed(isInsideInfoTarget(target, event));
			}
			else if (action == MotionEvent.ACTION_CANCEL) {
				clearInfoTouchTarget();
			}
			return true;
		}
		return super.dispatchTouchEvent(event);
	}

	private View findInfoTouchTarget(MotionEvent event) {
		int position = pointToPosition((int) event.getX(), (int) event.getY());
		if (position == INVALID_POSITION)
			return null;

		View row = getChildAt(position - getFirstVisiblePosition());
		if (row == null)
			return null;

		View info = row.findViewById(R.id.show_info);
		return isInsideInfoTarget(info, event) ? info : null;
	}

	private boolean isInsideInfoTarget(View target, MotionEvent event) {
		return target != null
				&& target.getVisibility() == View.VISIBLE
				&& target.isEnabled()
				&& target.getGlobalVisibleRect(mInfoTouchBounds)
				&& mInfoTouchBounds.contains((int) event.getRawX(),
						(int) event.getRawY());
	}

	private void clearInfoTouchTarget() {
		if (mInfoTouchTarget != null) {
			mInfoTouchTarget.setPressed(false);
			mInfoTouchTarget = null;
		}
	}
}
