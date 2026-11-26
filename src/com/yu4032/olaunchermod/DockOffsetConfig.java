package com.yu4032.olaunchermod;

/**
 * Per-orientation vertical offset (dp) for ColorOS Launcher Hotseat (Dock).
 * Positive values shift the Dock downward (reduce bottomMargin); negative
 * values shift the Dock upward (increase bottomMargin). Zero keeps native
 * geometry untouched. Landscape and portrait settings are independent.
 */
final class DockOffsetConfig {
    static final int LIMIT_DP = 300;
    static final DockOffsetConfig DEFAULT = new DockOffsetConfig(0, 0);

    private final int landscapeDp;
    private final int portraitDp;

    private DockOffsetConfig(int landscapeDp, int portraitDp) {
        this.landscapeDp = landscapeDp;
        this.portraitDp = portraitDp;
    }

    static DockOffsetConfig of(int landscapeDp, int portraitDp) {
        validate(landscapeDp);
        validate(portraitDp);
        return new DockOffsetConfig(landscapeDp, portraitDp);
    }

    static DockOffsetConfig parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("missing dock offset settings");
        }
        String[] values = text.trim().split("\\s+");
        if (values.length != 2) {
            throw new IllegalArgumentException("expected 2 values: landscape_dp portrait_dp");
        }
        int land = Integer.parseInt(values[0]);
        int port = Integer.parseInt(values[1]);
        validate(land);
        validate(port);
        return new DockOffsetConfig(land, port);
    }

    private static void validate(int value) {
        if (value < -LIMIT_DP || value > LIMIT_DP) {
            throw new IllegalArgumentException("dock offset outside -300..300 dp");
        }
    }

    String serialize() {
        return landscapeDp + " " + portraitDp;
    }

    int get(boolean portraitMode) {
        return portraitMode ? portraitDp : landscapeDp;
    }

    DockOffsetConfig with(boolean portraitMode, int value) {
        validate(value);
        return portraitMode
                ? new DockOffsetConfig(landscapeDp, value)
                : new DockOffsetConfig(value, portraitDp);
    }

    DockOffsetConfig resetOrientation(boolean portraitMode) {
        return portraitMode
                ? new DockOffsetConfig(landscapeDp, 0)
                : new DockOffsetConfig(0, portraitDp);
    }

    boolean isDefault() {
        return landscapeDp == 0 && portraitDp == 0;
    }

    /**
     * Computes adjusted FrameLayout bottomMargin in pixels for the active orientation.
     * Positive dp moves Dock down (reduces bottomMargin); negative dp moves Dock up.
     */
    int adjustBottomMargin(int nativeBottomPx, float density, int dockHeightPx,
            int containerHeightPx, boolean portraitMode) {
        int offsetDp = get(portraitMode);
        if (offsetDp == 0) return nativeBottomPx;
        if (!(density > 0)) density = 1f;
        int deltaPx = Math.round(offsetDp * density);
        int candidate = nativeBottomPx - deltaPx;

        // Allow slight negative bottomMargin (up to half the Dock height) so users can
        // lower the Dock close to the screen edge without losing touch targets, while
        // preventing it from moving off-screen upward past 45% of container height.
        int minBottom = dockHeightPx > 0 ? -Math.round(dockHeightPx * 0.5f) : -Math.round(60 * density);
        int maxBottom = containerHeightPx > 0
                ? Math.max(nativeBottomPx, Math.round(containerHeightPx * 0.45f))
                : Integer.MAX_VALUE;
        return Math.max(minBottom, Math.min(maxBottom, candidate));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DockOffsetConfig that)) return false;
        return landscapeDp == that.landscapeDp && portraitDp == that.portraitDp;
    }

    @Override
    public int hashCode() {
        return 31 * landscapeDp + portraitDp;
    }
}
