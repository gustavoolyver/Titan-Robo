package titan.gun;

import java.awt.geom.Point2D;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;

/**
 * Mira circular: supõe que o inimigo continua curvando na mesma taxa.
 * Ideia inspirada no SuperRamFire (Super Sample Robots do Robocode, CrazyBassoonist).
 */
public class CircularTargeting implements Targeting {
    @Override
    public double aimAngle(Enemy enemy, double myX, double myY,
                           double bulletSpeed, BattleField field) {
        Point2D.Double p = PositionPredictor.predict(
                enemy, myX, myY, bulletSpeed, field, enemy.headingChange);
        return Geometry.absoluteBearing(myX, myY, p.x, p.y);
    }
}
