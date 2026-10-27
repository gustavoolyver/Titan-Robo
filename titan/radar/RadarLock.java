package titan.radar;

import robocode.AdvancedRobot;
import robocode.util.Utils;
import titan.utils.Enemy;
import titan.utils.Geometry;

/** Radar: varre até achar um alvo e depois mantém o radar travado nele. */
public class RadarLock {
    private static final long MAX_TICKS_WITHOUT_SCAN = 3;

    private final AdvancedRobot robot;

    public RadarLock(AdvancedRobot robot) {
        this.robot = robot;
    }

    public void initRound() {
        robot.setAdjustRadarForGunTurn(true);
        robot.setAdjustRadarForRobotTurn(true);
        robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    public void execute(Enemy target) {
        if (target == null || robot.getTime() - target.lastSeen > MAX_TICKS_WITHOUT_SCAN) {
            robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            return;
        }
        double bearing = Geometry.absoluteBearing(
                robot.getX(), robot.getY(), target.x, target.y);
        double turn = Utils.normalRelativeAngle(bearing - robot.getRadarHeadingRadians());
        // girar o dobro do ângulo faz o radar passar um pouco do alvo e não perdê-lo
        robot.setTurnRadarRightRadians(turn * 2);
    }
}
