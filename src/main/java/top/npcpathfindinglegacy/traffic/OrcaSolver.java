package top.npcpathfindinglegacy.traffic;

import java.util.ArrayList;
import java.util.List;

/** Two-dimensional ORCA half-planes in blocks/tick. Immutable numbers only. */
public final class OrcaSolver {
    public static final class Velocity {
        public static final Velocity ZERO = new Velocity(0, 0);
        public final double x, z;
        public Velocity(double x, double z) { this.x = x; this.z = z; }
        public Velocity add(Velocity b) { return new Velocity(x + b.x, z + b.z); }
        public Velocity subtract(Velocity b) { return new Velocity(x - b.x, z - b.z); }
        public Velocity scale(double s) { return new Velocity(x * s, z * s); }
        public double dot(Velocity b) { return x * b.x + z * b.z; }
        public double length() { return Math.hypot(x, z); }
        public Velocity limit(double max) { double n = length(); return n > max ? scale(max / n) : this; }
    }
    public static final class Agent {
        final long id;
        final double x, z, vx, vz, radius, comfort;
        final boolean reciprocal;
        public Agent(long id, double x, double z, double vx, double vz, double radius, double comfort, boolean reciprocal) {
            this.id = id; this.x = x; this.z = z; this.vx = vx; this.vz = vz;
            this.radius = radius; this.comfort = comfort; this.reciprocal = reciprocal;
        }
    }
    private static final class Plane {
        final Velocity normal;
        final double offset;
        Plane(Velocity normal, double offset) { this.normal = normal; this.offset = offset; }
    }
    private OrcaSolver() {}

    public static Velocity solve(Agent self, List<Agent> neighbors, Velocity preferred, double maxSpeed, boolean narrow, boolean axisOnly) {
        Velocity current = new Velocity(self.vx, self.vz), separation = Velocity.ZERO;
        List<Plane> planes = new ArrayList<>(neighbors.size());
        double speedLimit = maxSpeed;
        boolean approaching = false;
        for (Agent other : neighbors) {
            Velocity p = new Velocity(other.x - self.x, other.z - self.z);
            double distance = p.length(), radius = self.radius + other.radius + 0.03;
            double comfort = radius + (self.comfort + other.comfort) * (narrow ? 0.1 : 0.5);
            approaching |= distance < Math.max(comfort, radius + maxSpeed * 12) && preferred.dot(p) > 0;
            if (distance < comfort) {
                Velocity away = distance > 1.0E-6 ? p.scale(-1 / distance) : new Velocity(self.id < other.id ? -1 : 1, 0);
                double strength = Math.min(1, (comfort - distance) / Math.max(0.1, comfort - radius));
                separation = separation.add(away.scale(strength * strength * maxSpeed * 0.65));
                if (preferred.dot(p) > 0 && distance < radius + (comfort - radius) * 0.3) {
                    speedLimit = Math.min(speedLimit, maxSpeed * Math.max(0, (distance - radius) / Math.max(0.1, (comfort - radius) * 0.3)));
                }
            }
            Velocity relative = current.subtract(new Velocity(other.vx, other.vz));
            Velocity normal, correction;
            if (distance > radius) {
                double horizon = 12;
                Velocity w = relative.subtract(p.scale(1 / horizon));
                double dot = w.dot(p), w2 = w.dot(w);
                if (dot < 0 && dot * dot > radius * radius * w2) {
                    double length = w.length();
                    normal = w.scale(1 / Math.max(1.0E-9, length));
                    correction = normal.scale(radius / horizon - length);
                } else {
                    double leg = Math.sqrt(Math.max(0, distance * distance - radius * radius));
                    double cross = p.x * w.z - p.z * w.x;
                    Velocity tangent = cross > 0
                        ? new Velocity(p.x * leg - p.z * radius, p.x * radius + p.z * leg)
                        : new Velocity(-p.x * leg - p.z * radius, p.x * radius - p.z * leg);
                    tangent = tangent.scale(1 / (distance * distance));
                    normal = new Velocity(-tangent.z, tangent.x);
                    correction = tangent.scale(relative.dot(tangent)).subtract(relative);
                }
            } else {
                Velocity w = relative.subtract(p);
                double length = w.length();
                normal = length > 1.0E-9 ? w.scale(1 / length) : new Velocity(self.id < other.id ? -1 : 1, 0);
                correction = normal.scale(radius - length);
            }
            Velocity point = current.add(correction.scale(other.reciprocal ? 0.5 : 1));
            planes.add(new Plane(normal, normal.dot(point)));
        }
        Velocity desired = preferred.add(separation).limit(speedLimit);
        // A local right-hand preference breaks mirror symmetry without steering in empty space.
        if (approaching && !narrow) desired = desired.add(new Velocity(preferred.z, -preferred.x).scale(0.06)).limit(speedLimit);
        if (axisOnly) {
            double length = preferred.length();
            if (length < 1.0E-9) return Velocity.ZERO;
            Velocity axis = preferred.scale(1 / length);
            double low = 0, high = Math.min(length,speedLimit);
            for (Plane plane : planes) {
                double projection = plane.normal.dot(axis);
                if (projection > 1.0E-8) low = Math.max(low,plane.offset / projection);
                else if (projection < -1.0E-8) high = Math.min(high,plane.offset / projection);
                else if (plane.offset > 1.0E-8) return Velocity.ZERO;
            }
            return low <= high ? axis.scale(Math.max(low,Math.min(high,desired.dot(axis)))) : Velocity.ZERO;
        }
        Velocity result = desired;
        for (int i = 0; i < planes.size(); i++) {
            Plane plane = planes.get(i);
            if (plane.normal.dot(result) >= plane.offset - 1.0E-8) continue;
            if (plane.offset > speedLimit) return Velocity.ZERO;
            Velocity base = plane.normal.scale(plane.offset), tangent = new Velocity(-plane.normal.z, plane.normal.x);
            double extent = Math.sqrt(Math.max(0, speedLimit * speedLimit - plane.offset * plane.offset));
            double low = -extent, high = extent;
            for (int j = 0; j < i; j++) {
                Plane previous = planes.get(j);
                double slope = previous.normal.dot(tangent), required = previous.offset - previous.normal.dot(base);
                if (Math.abs(slope) < 1.0E-8) {
                    if (required > 1.0E-8) return Velocity.ZERO;
                } else if (slope > 0) low = Math.max(low, required / slope);
                else high = Math.min(high, required / slope);
                if (low > high + 1.0E-8) return Velocity.ZERO;
            }
            double t = Math.max(low, Math.min(high, desired.subtract(base).dot(tangent)));
            result = base.add(tangent.scale(t));
        }
        return result;
    }

}
