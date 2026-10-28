package titan.move;

import robocode.ScannedRobotEvent;

public class MoveEnemy {
    public String name;
    public double x, y;
    public double distance;
    public double energy;
    public double velocity;
    public double heading;

    public void update(ScannedRobotEvent e, double myX, double myY, double myHeading) {
        this.name = e.getName();
        this.distance = e.getDistance();
        this.energy = e.getEnergy();
        this.velocity = e.getVelocity();
        this.heading = e.getHeadingRadians();
        
        double angle = myHeading + e.getBearingRadians();
        this.x = myX + Math.sin(angle) * this.distance;
        this.y = myY + Math.cos(angle) * this.distance;
    }
}
