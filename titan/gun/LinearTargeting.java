package titan.gun;

import java.awt.geom.Point2D;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;

/** Mira linear: supõe que o inimigo segue em linha reta. */
public class LinearTargeting implements Targeting {
    @Override
    public double aimAngle(Enemy enemy, double myX, double myY,
                           double bulletSpeed, BattleField field) {
        Point2D.Double p = PositionPredictor.predict(
                enemy, myX, myY, bulletSpeed, field, 0);
        return Geometry.absoluteBearing(myX, myY, p.x, p.y);
    }
}
