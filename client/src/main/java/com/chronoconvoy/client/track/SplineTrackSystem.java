package com.chronoconvoy.client.track;

import com.badlogic.gdx.math.CatmullRomSpline;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;
import com.chronoconvoy.client.config.ConvoyConfig;

import java.util.Objects;

/**
 * [CLI-01] Continuous scrolling wasteland Catmull-Rom spline track with deterministic procedural
 * segment generation, fork switching (MAIN_LINE / LEFT_BRANCH / RIGHT_BRANCH), curvature evaluation,
 * and zero-allocation pooled TrackSample queries.
 */
public final class SplineTrackSystem {

    public enum ForkDirection {
        MAIN_LINE(0.0f),
        LEFT_BRANCH(1.0f),
        RIGHT_BRANCH(-1.0f);

        private final float lateralSign;

        ForkDirection(float lateralSign) {
            this.lateralSign = lateralSign;
        }

        public float getLateralSign() {
            return lateralSign;
        }
    }

    /**
     * Poolable track sample holding world position, unit tangent, unit normal, and curvature at a queried distance.
     */
    public static final class TrackSample implements Pool.Poolable {
        public final Vector2 position = new Vector2();
        public final Vector2 tangent = new Vector2(1.0f, 0.0f);
        public final Vector2 normal = new Vector2(0.0f, 1.0f);
        public float distanceMeters;
        public float curvature;
        public ForkDirection activeBranch = ForkDirection.MAIN_LINE;

        @Override
        public void reset() {
            position.setZero();
            tangent.set(1.0f, 0.0f);
            normal.set(0.0f, 1.0f);
            distanceMeters = 0.0f;
            curvature = 0.0f;
            activeBranch = ForkDirection.MAIN_LINE;
        }
    }

    private static final int WINDOW_CONTROL_POINTS = 16;
    private static final int LEAD_SAFE_SEGMENTS = 8;

    private final ConvoyConfig config;
    private final long seed;
    private final float segmentLength;
    private final Vector2[] controlPoints;
    private final CatmullRomSpline<Vector2> spline;

    // Pre-allocated scratch vectors for zero-allocation hot-path sampling
    private final Vector2 scratchPos = new Vector2();
    private final Vector2 scratchDeriv = new Vector2();
    private final Vector2 scratchAhead = new Vector2();
    private final Vector2 scratchBehind = new Vector2();

    private final Pool<TrackSample> samplePool = new Pool<TrackSample>(32, 128) {
        @Override
        protected TrackSample newObject() {
            return new TrackSample();
        }
    };

    private long baseSegmentIndex = -2;
    private ForkDirection activeFork = ForkDirection.MAIN_LINE;
    private ForkDirection queuedFork = ForkDirection.MAIN_LINE;
    private int forkSwitchCount = 0;
    private float currentForkOffsetMeters = 0.0f;
    private float targetForkOffsetMeters = 0.0f;
    private long nextGeneratedSegmentIndex = 0;

    public SplineTrackSystem(ConvoyConfig config, long seed) {
        this.config = Objects.requireNonNull(config, "config");
        this.seed = seed;
        this.segmentLength = config.getTrackSegmentLengthMeters();
        this.controlPoints = new Vector2[WINDOW_CONTROL_POINTS];
        for (int i = 0; i < WINDOW_CONTROL_POINTS; i++) {
            this.controlPoints[i] = new Vector2();
        }
        rebuildInitialWindow();
        this.spline = new CatmullRomSpline<>(this.controlPoints, false);
    }

    private void rebuildInitialWindow() {
        baseSegmentIndex = -2;
        currentForkOffsetMeters = 0.0f;
        targetForkOffsetMeters = 0.0f;
        for (int i = 0; i < WINDOW_CONTROL_POINTS; i++) {
            long segIdx = baseSegmentIndex + i;
            computeControlPoint(segIdx, controlPoints[i]);
        }
        nextGeneratedSegmentIndex = baseSegmentIndex + WINDOW_CONTROL_POINTS;
    }

    private void computeControlPoint(long segmentIndex, Vector2 outPoint) {
        float x = segmentIndex * segmentLength;
        // Deterministic wasteland undulation + fork branch lateral offset
        float phase = (segmentIndex * 0.37f) + ((seed & 0xFFFFL) * 0.013f);
        float secondaryPhase = (segmentIndex * 0.19f) - (((seed >>> 16) & 0xFFFFL) * 0.007f);
        float baseUndulation = MathUtils.sin(phase) * 8.5f + MathUtils.cos(secondaryPhase) * 4.0f;
        if (segmentIndex <= 0) {
            // Keep spawn strip level so initial locomotive/carriage placement is aligned
            baseUndulation *= MathUtils.clamp((segmentIndex + 2.0f) / 3.0f, 0.0f, 1.0f);
        }
        // Smoothly step currentForkOffsetMeters toward targetForkOffsetMeters as new segments stream in
        if (segmentIndex > 2) {
            float delta = targetForkOffsetMeters - currentForkOffsetMeters;
            if (Math.abs(delta) > 0.001f) {
                currentForkOffsetMeters += Math.signum(delta) * Math.min(Math.abs(delta), config.getForkLateralOffsetMeters() * 0.45f);
            }
        }
        outPoint.set(x, baseUndulation + currentForkOffsetMeters);
    }

    /**
     * Advances the sliding Catmull-Rom control point window as the locomotive progresses along the track.
     * Executes in O(1) amortized time with zero heap allocations.
     */
    public void updateScrollWindow(float leadDistanceMeters) {
        long currentSegment = (long) Math.floor(leadDistanceMeters / segmentLength);
        long desiredBase = currentSegment - 4;
        while (baseSegmentIndex < desiredBase) {
            // Shift control points left in-place without allocating new Vector2 objects
            for (int i = 0; i < WINDOW_CONTROL_POINTS - 1; i++) {
                controlPoints[i].set(controlPoints[i + 1]);
            }
            if (queuedFork != activeFork) {
                activeFork = queuedFork;
                targetForkOffsetMeters = activeFork.getLateralSign() * config.getForkLateralOffsetMeters();
            }
            computeControlPoint(nextGeneratedSegmentIndex, controlPoints[WINDOW_CONTROL_POINTS - 1]);
            nextGeneratedSegmentIndex++;
            baseSegmentIndex++;
        }
    }

    /**
     * Switches the convoy onto the specified fork branch at the next track junction segment.
     */
    public void switchFork(ForkDirection direction) {
        Objects.requireNonNull(direction, "direction");
        if (this.queuedFork != direction) {
            this.queuedFork = direction;
            this.activeFork = direction;
            this.targetForkOffsetMeters = direction.getLateralSign() * config.getForkLateralOffsetMeters();
            this.forkSwitchCount++;
            // Apply immediate branch shaping to upcoming forward control points ahead of the train
            for (int i = 3; i < WINDOW_CONTROL_POINTS; i++) {
                long segIdx = baseSegmentIndex + i;
                computeControlPoint(segIdx, controlPoints[i]);
            }
        }
    }

    public TrackSample obtainSample() {
        return samplePool.obtain();
    }

    public void freeSample(TrackSample sample) {
        if (sample != null) {
            samplePool.free(sample);
        }
    }

    public int getFreeSamplePoolCount() {
        return samplePool.getFree();
    }

    /**
     * Evaluates the continuous Catmull-Rom spline at the requested arc-length distance (meters)
     * and writes position, unit tangent, unit normal, and curvature into outSample with zero allocations.
     */
    public TrackSample sampleAtDistance(float distanceMeters, TrackSample outSample) {
        Objects.requireNonNull(outSample, "outSample");
        updateScrollWindow(distanceMeters);

        float localSegmentsFromBase = (distanceMeters / segmentLength) - baseSegmentIndex;
        // CatmullRomSpline in non-continuous mode spans (N - 3) spans between index 1 and N - 2
        int spanCount = WINDOW_CONTROL_POINTS - 3;
        float clampedSpanPos = MathUtils.clamp(localSegmentsFromBase - 1.0f, 0.0001f, spanCount - 0.0001f);
        float u = clampedSpanPos / spanCount;

        spline.valueAt(scratchPos, u);
        spline.derivativeAt(scratchDeriv, u);
        if (scratchDeriv.len2() < 1e-6f) {
            scratchDeriv.set(1.0f, 0.0f);
        } else {
            scratchDeriv.nor();
        }

        outSample.position.set(scratchPos);
        outSample.tangent.set(scratchDeriv);
        outSample.normal.set(-scratchDeriv.y, scratchDeriv.x);
        outSample.distanceMeters = distanceMeters;
        outSample.activeBranch = activeFork;

        // Finite-difference tangent angle rate for signed curvature (rad/m)
        float du = Math.min(0.005f, 1.0f / (spanCount * 20.0f));
        float uBehind = MathUtils.clamp(u - du, 0.0f, 1.0f);
        float uAhead = MathUtils.clamp(u + du, 0.0f, 1.0f);
        spline.derivativeAt(scratchBehind, uBehind).nor();
        spline.derivativeAt(scratchAhead, uAhead).nor();

        float cross = scratchBehind.crs(scratchAhead);
        float dot = MathUtils.clamp(scratchBehind.dot(scratchAhead), -1.0f, 1.0f);
        float dTheta = MathUtils.atan2(cross, dot);
        float ds = Math.max(0.1f, (uAhead - uBehind) * spanCount * segmentLength);
        outSample.curvature = dTheta / ds;

        return outSample;
    }

    /**
     * Computes the signed lateral drift (in meters) of a world position relative to the spline track
     * at the given longitudinal distance.
     */
    public float computeLateralDriftMeters(float longitudinalDistanceMeters, Vector2 worldPosition) {
        TrackSample sample = samplePool.obtain();
        try {
            sampleAtDistance(longitudinalDistanceMeters, sample);
            float dx = worldPosition.x - sample.position.x;
            float dy = worldPosition.y - sample.position.y;
            return dx * sample.normal.x + dy * sample.normal.y;
        } finally {
            samplePool.free(sample);
        }
    }

    public ForkDirection getActiveFork() {
        return activeFork;
    }

    public ForkDirection getQueuedFork() {
        return queuedFork;
    }

    public int getForkSwitchCount() {
        return forkSwitchCount;
    }

    public long getBaseSegmentIndex() {
        return baseSegmentIndex;
    }

    public float getSegmentLength() {
        return segmentLength;
    }
}
