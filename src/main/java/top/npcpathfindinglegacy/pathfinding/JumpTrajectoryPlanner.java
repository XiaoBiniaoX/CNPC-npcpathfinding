package top.npcpathfindinglegacy.pathfinding;

final class JumpTrajectoryPlanner {
    static final double MAX_JUMP_VELOCITY = 0.50D;
    static final double MAX_PARKOUR_SPEED = 0.54D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final int MAX_FLIGHT_TICKS = 32;

    private JumpTrajectoryPlanner() {
    }

    static double parkourVelocity(int tendency, int rise) {
        double speed = 0.37 + 0.13 * tendency / 100.0 + Math.max(0, rise) * 0.055;
        if (rise <= -2) speed = Math.max(0.38, speed - 0.04);
        return ensureReachableVerticalSpeed(speed, Math.max(0, rise));
    }

    static int estimateFlightTicks(double startY, double initialVelocityY, double landingY) {
        double y = startY;
        double velocityY = initialVelocityY;
        boolean reachedLandingHeight = startY >= landingY - 0.02D;

        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            y += velocityY;
            if (y >= landingY + 0.02D) {
                reachedLandingHeight = true;
            }
            if (reachedLandingHeight && velocityY <= 0.0D && y <= landingY) {
                return tick;
            }
            velocityY = (velocityY - GRAVITY) * VERTICAL_DRAG;
        }
        return MAX_FLIGHT_TICKS;
    }

    static double horizontalSpeed(double remainingDistance, int remainingTicks, double maxSpeed) {
        if (remainingDistance <= 0.0D || maxSpeed <= 0.0D) {
            return 0.0D;
        }
        int ticks = Math.max(1, remainingTicks);
        return Math.min(maxSpeed, remainingDistance / ticks);
    }

    static double ensureReachableVerticalSpeed(double requestedSpeed, int rise) {
        if (rise <= 0) {
            return Math.min(MAX_JUMP_VELOCITY, requestedSpeed);
        }
        double speed = Math.min(MAX_JUMP_VELOCITY, Math.max(0.35D, requestedSpeed));
        double requiredApex = rise + 0.12D;
        while (speed < MAX_JUMP_VELOCITY && apexHeight(speed) < requiredApex) {
            speed += 0.01D;
        }
        return Math.min(MAX_JUMP_VELOCITY, speed);
    }

    static double apexHeight(double initialVelocityY) {
        double y = 0.0D;
        double velocityY = initialVelocityY;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS && velocityY > 0.0D; tick++) {
            y += velocityY;
            velocityY = (velocityY - GRAVITY) * VERTICAL_DRAG;
        }
        return y;
    }
}
