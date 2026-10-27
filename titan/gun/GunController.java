package titan.gun;

import java.awt.geom.Point2D;
import robocode.AdvancedRobot;
import robocode.util.Utils;
import titan.stats.ShotStats;
import titan.utils.BattleField;
import titan.utils.Enemy;

/** Arma: escolhe a potência, mira com a estratégia configurada e atira. */
public class GunController {
    private static final double MIN_POWER = 0.1;
    private static final double ROBOT_HALF_WIDTH = 18;
    private static final double MAX_FIRE_TOLERANCE = Math.toRadians(5);
    private static final double MIN_FIRE_TOLERANCE = Math.toRadians(1);

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
        if (target == null) {
            return;
        }
        targeting.onTick(target, robot.getTime());

        double myX = robot.getX();
        double myY = robot.getY();
        double distance = Point2D.distance(myX, myY, target.x, target.y);

        double power = selectPower(target, distance);
        double bulletSpeed = 20 - 3 * power;
        targeting.onAim(target, myX, myY, bulletSpeed, robot.getTime());

        double aimAngle = targeting.aimAngle(target, myX, myY, bulletSpeed, field);
        double gunTurn = Utils.normalRelativeAngle(aimAngle - robot.getGunHeadingRadians());
        robot.setTurnGunRightRadians(gunTurn);

        if (robot.getGunHeat() == 0
                && Math.abs(robot.getGunTurnRemainingRadians()) < fireTolerance(distance)
                && robot.getEnergy() > power
                && robot.setFireBullet(power) != null) {
            stats.shotFired();
            targeting.onFire(target, myX, myY, bulletSpeed, robot.getTime());
        }
    }

    /**
     * Potência pela distância, limitada pela nossa energia e pelo que é
     * necessário para matar o alvo (não gasta energia à toa num inimigo quase morto).
     */
    private double selectPower(Enemy target, double distance) {
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
        power = Math.min(power, Math.max(MIN_POWER, robot.getEnergy() / 5));
        power = Math.min(power, powerToKill(target.energy));
        return Math.max(MIN_POWER, power);
    }

    /** Menor potência cujo dano mata um inimigo com essa energia. */
    private static double powerToKill(double enemyEnergy) {
        if (enemyEnergy <= 4) {
            return enemyEnergy / 4;
        }
        return (enemyEnergy + 2) / 6;
    }

    /** Quanto o canhão pode estar fora da mira: menor quanto mais longe o alvo. */
    private static double fireTolerance(double distance) {
        double angle = Math.atan(ROBOT_HALF_WIDTH / Math.max(distance, 1));
        return Math.max(MIN_FIRE_TOLERANCE, Math.min(MAX_FIRE_TOLERANCE, angle));
    }
}
