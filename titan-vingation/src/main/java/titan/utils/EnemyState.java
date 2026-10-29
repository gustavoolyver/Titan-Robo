package titan.utils;

import robocode.util.Utils;

/** Último estado conhecido de um inimigo. */
public class EnemyState {
    public final String name;
    public double x;
    public double y;
    public double heading;
    public double velocity;
    public double distance;
    public double energy;
    /** Variação de rumo por tick (radianos), calculada entre dois scans. */
    public double headingChange;
    public long lastSeen = -1;

    private double previousEnergy;
    private boolean seenBefore;

    public EnemyState(String name) {
        this.name = name;
    }

    public void update(double x, double y, double heading, double velocity,
                       double distance, double energy, long time) {
        if (seenBefore) {
            long ticks = Math.max(1, time - lastSeen);
            headingChange = Utils.normalRelativeAngle(heading - this.heading) / ticks;
            previousEnergy = this.energy;
        } else {
            headingChange = 0;
            previousEnergy = energy;
        }
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.velocity = velocity;
        this.distance = distance;
        this.energy = energy;
        this.lastSeen = time;
        this.seenBefore = true;
    }

    /** Queda de energia desde o scan anterior (positivo = perdeu energia). */
    public double energyDrop() {
        return previousEnergy - energy;
    }
}
