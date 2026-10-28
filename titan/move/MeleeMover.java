package titan.move;

import titan.move.formulas.SimpleFormula;

public class MeleeMover {
    public double getDestinationRisk(double myX, double myY, double enemyX, double enemyY, double fieldWidth, double fieldHeight) {
        double distToWall = Math.min(Math.min(myX, fieldWidth - myX), Math.min(myY, fieldHeight - myY));
        double distToEnemy = Math.hypot(enemyX - myX, enemyY - myY);
        return SimpleFormula.calculateRisk(distToWall, distToEnemy);
    }
}
