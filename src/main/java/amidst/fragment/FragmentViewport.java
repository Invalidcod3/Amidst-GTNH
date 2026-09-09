package amidst.fragment;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

/** Immutable EDT snapshot. Background scheduling must never traverse the mutable graph. */
public final class FragmentViewport {
	private final double left, top, right, bottom;

	public FragmentViewport(CoordinatesInWorld topLeft, CoordinatesInWorld bottomRight) {
		left = topLeft.getX();
		top = topLeft.getY();
		right = bottomRight.getX();
		bottom = bottomRight.getY();
	}

	public boolean isVisible(Fragment fragment) {
		CoordinatesInWorld corner = fragment.getCorner();
		return corner.getX() < right && corner.getX() + (double) Fragment.SIZE > left
				&& corner.getY() < bottom && corner.getY() + (double) Fragment.SIZE > top;
	}
}
