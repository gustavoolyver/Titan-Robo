package titan.gun;

import java.awt.geom.Point2D;
import robocode.AdvancedRobot;
import robocode.util.Utils;
import titan.stats.ShotStatistics;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;

/** Arma: escolhe a potência, mira com a estratégia configurada e atira. */
public class GunController {


    private final AdvancedRobot robot;
    private final BattlefieldBounds field;
    private final TargetingStrategy targeting;
    private final ShotStatistics stats;

    public GunController(AdvancedRobot robot, BattlefieldBounds field,
                         TargetingStrategy targeting, ShotStatistics stats) {
        this.robot = robot;
        this.field = field;
        this.targeting = targeting;
        this.stats = stats;
    }

    public void initRound() {
        robot.setAdjustGunForRobotTurn(true);
        robot.setAdjustRadarForGunTurn(true);
    }

    public void execute(EnemyState target) {
        if (target == null || robot.getTime() - target.lastSeen > 1) {
            return;
        }
        double myX = robot.getX();
        double myY = robot.getY();
        double distance = Point2D.distance(myX, myY, target.x, target.y);

        double power = selectPower(distance, target.energy);
        double bulletSpeed = 20 - 3 * power;

        long time = robot.getTime();
        // Pontua as ondas antigas antes de criar a onda deste turno.
        targeting.onAim(target, myX, myY, bulletSpeed, time);
        double aimAngle = targeting.aimAngle(target, myX, myY, bulletSpeed, field);
        double gunTurn = Utils.normalRelativeAngle(aimAngle - robot.getGunHeadingRadians());
        robot.setTurnGunRightRadians(gunTurn);

        if (robot.getGunHeat() == 0
                && Math.abs(gunTurn) < Math.min(Math.toRadians(5), Math.atan(18.0 / Math.max(1, distance)))
                && robot.getEnergy() > power
                && robot.setFireBullet(power) != null) {
            stats.shotFired();
            targeting.onFire(target, myX, myY, bulletSpeed, time);
        }
    }

    /** Score waves on every fresh scan, including non-selected opponents in melee. */
    public void onScannedRobot(EnemyState enemy) {
        targeting.onTick(enemy, robot.getTime());
    }

    private double selectPower(double distance, double enemyEnergy) {
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
        double finishingPower = enemyEnergy <= 4 ? enemyEnergy / 4 : (enemyEnergy + 2) / 6;
        return Math.max(0.1, Math.min(finishingPower,
                Math.min(power, robot.getEnergy() / 5)));
    }
}
