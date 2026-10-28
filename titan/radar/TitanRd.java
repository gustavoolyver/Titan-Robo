package titan.radar;

import robocode.AdvancedRobot;

public class TitanRd {
    private final AdvancedRobot robot;

    public TitanRd(AdvancedRobot robot) {
        this.robot = robot;
    }

    public void updateRadar(double enemyBearingRadians) {
        double radarTurn = robot.getHeadingRadians() + enemyBearingRadians - robot.getRadarHeadingRadians();
        robot.setTurnRadarRightRadians(robocode.util.Utils.normalRelativeAngle(radarTurn) * 1.9);
    }
}
