package titan.gun;

import java.awt.geom.Point2D;
import robocode.AdvancedRobot;
import robocode.util.Utils;
import titan.stats.ShotStats;
import titan.utils.BattleField;
import titan.utils.Enemy;

/** Arma: escolhe a potência, mira com a estratégia configurada e atira. */
public class GunController {
    private static final double FIRE_TOLERANCE = Math.toRadians(5);

    private final AdvancedRobot robot;
    private final BattleField field;
    private final Targeting targeting;
    private final ShotStats stats;

    public GunController(AdvancedRobot robot, BattleField field,
                         Targeting targeting, ShotStats stats) {
        this.robot = robot;
        this.field = field;
        this.targeting = targeting;
        this.stats = stats;
    }

    public void initRound() {
        robot.setAdjustGunForRobotTurn(true);
        robot.setAdjustRadarForGunTurn(true);
    }

    public void execute(Enemy target) {
        if (target == null || robot.getTime() - target.lastSeen > 1) {
            return;
        }
        double myX = robot.getX();
        double myY = robot.getY();
        double distance = Point2D.distance(myX, myY, target.x, target.y);

        double power = selectPower(distance);
        double bulletSpeed = 20 - 3 * power;

        long time = robot.getTime();
        targeting.onTick(target, time);
        // Pontua as ondas antigas antes de criar a onda deste turno.
        targeting.onAim(target, myX, myY, bulletSpeed, time);
        double aimAngle = targeting.aimAngle(target, myX, myY, bulletSpeed, field);
        double gunTurn = Utils.normalRelativeAngle(aimAngle - robot.getGunHeadingRadians());
        robot.setTurnGunRightRadians(gunTurn);

        if (robot.getGunHeat() == 0
                && Math.abs(gunTurn) < FIRE_TOLERANCE
                && robot.getEnergy() > power
                && robot.setFireBullet(power) != null) {
            stats.shotFired();
            targeting.onFire(target, myX, myY, bulletSpeed, time);
        }
    }

    private double selectPower(double distance) {
        double power;
        if (robot.getEnergy() < 15) {
            power = 0.8;
        } else if (distance > 500) {
            power = 1.0;
        } else if (distance > 250) {
            power = 1.7;
        } else {
            power = 2.2;
        }
        return Math.min(power, Math.max(0.1, robot.getEnergy() / 5));
    }
}
