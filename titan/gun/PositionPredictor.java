package titan.gun;

import java.awt.geom.Point2D;
import titan.utils.BattleField;
import titan.utils.Enemy;

/** Simula o inimigo tick a tick até a bala chegar nele. */
final class PositionPredictor {
    private static final int MAX_TICKS = 150;

    private PositionPredictor() {
    }

    /** turnRate = variação de rumo por tick (0 = linha reta). */
    static Point2D.Double predict(Enemy enemy, double myX, double myY,
                                  double bulletSpeed, BattleField field, double turnRate) {
        double x = enemy.x;
        double y = enemy.y;
        double heading = enemy.heading;
        int ticks = 0;

        while (++ticks <= MAX_TICKS
                && ticks * bulletSpeed < Point2D.distance(myX, myY, x, y)) {
            x += Math.sin(heading) * enemy.velocity;
            y += Math.cos(heading) * enemy.velocity;
            heading += turnRate;
            x = field.clampX(x);
            y = field.clampY(y);
        }
        return new Point2D.Double(x, y);
    }
}
