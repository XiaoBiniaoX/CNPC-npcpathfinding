package top.npcpathfindinglegacy.pathfinding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JumpTrajectoryPlannerTest {
    @Test
    public void capsJumpSpeedEvenWhenAskedToReachTwoBlocks() {
        double speed = JumpTrajectoryPlanner.ensureReachableVerticalSpeed(0.48D, 2);

        assertEquals(0.50D, speed, 0.000001D);
        assertTrue(JumpTrajectoryPlanner.apexHeight(speed) < 2.0D);
    }

    @Test
    public void shorterLandingUsesLessHorizontalSpeed() {
        int ticks = JumpTrajectoryPlanner.estimateFlightTicks(64.0D, 0.45D, 64.0D);
        double shortHop = JumpTrajectoryPlanner.horizontalSpeed(2.0D, ticks, 0.54D);
        double longHop = JumpTrajectoryPlanner.horizontalSpeed(5.0D, ticks, 0.54D);

        assertTrue(ticks >= 10 && ticks <= 15);
        assertTrue(shortHop < longHop);
        assertTrue(longHop <= 0.54D);
    }

    @Test
    public void adaptiveHorizontalPlanConvergesOnLandingCenter() {
        assertPlanConverges(1.5D, 0, 0.45D, 0.54D);
        assertPlanConverges(3.0D, 0, 0.45D, 0.54D);
        assertPlanConverges(5.0D, 0, 0.45D, 0.54D);
        assertPlanConverges(3.0D, 1, 0.48D, 0.54D);
        assertPlanConverges(5.0D, -4, 0.41D, 0.54D);
    }

    private static void assertPlanConverges(
        double targetX,
        int rise,
        double requestedVelocityY,
        double maxHorizontalSpeed
    ) {
        double x = 0.0D;
        double y = 64.0D;
        double velocityY = JumpTrajectoryPlanner.ensureReachableVerticalSpeed(requestedVelocityY, rise);
        double targetY = y + rise;

        for (int tick = 0; tick < 32; tick++) {
            double distance = targetX - x;
            int remainingTicks = JumpTrajectoryPlanner.estimateFlightTicks(y, velocityY, targetY);
            double speed = JumpTrajectoryPlanner.horizontalSpeed(
                Math.abs(distance),
                remainingTicks,
                maxHorizontalSpeed
            );
            x += Math.copySign(speed, distance);
            y += velocityY;
            velocityY = (velocityY - 0.08D) * 0.98D;
            if (velocityY <= 0.0D && y <= targetY) {
                break;
            }
        }

        assertEquals(targetX, x, 0.20D);
    }
}
