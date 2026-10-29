package titan.radar;

import robocode.AdvancedRobot;
import robocode.util.Utils;
import titan.utils.EnemyState;
import titan.utils.Geometry;

/**
 * Radar 1v1: varre até achar o alvo e depois o mantém travado, girando até a
 * posição dele mais uma folga do tamanho do robô, para o arco de varredura
 * sempre cobrir o inimigo mesmo que ele (ou nós) se mova.
 */
public class RadarController {
    private static final long MAX_TICKS_WITHOUT_SCAN = 3;
    private static final double ROBOT_WIDTH = 36;

    private final AdvancedRobot robot;

    public RadarController(AdvancedRobot robot) {
        this.robot = robot;
    }

    public void initRound() {
        robot.setAdjustRadarForGunTurn(true);
        robot.setAdjustRadarForRobotTurn(true);
        robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    public void execute(EnemyState target) {
        if (target == null || robot.getOthers() > 1) {
            robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            return;
        }
        double distance = Math.max(1, Math.hypot(
                target.x - robot.getX(), target.y - robot.getY()));
        double bearing = Geometry.absoluteBearing(
                robot.getX(), robot.getY(), target.x, target.y);
        double turn = Utils.normalRelativeAngle(bearing - robot.getRadarHeadingRadians());

        if (robot.getTime() - target.lastSeen > MAX_TICKS_WITHOUT_SCAN) {
            // perdeu o alvo: gira sem parar até achá-lo de novo
            robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            return;
        }
        double extra = Math.atan(ROBOT_WIDTH / distance);
        robot.setTurnRadarRightRadians(turn + (turn >= 0 ? extra : -extra));
    }
}
