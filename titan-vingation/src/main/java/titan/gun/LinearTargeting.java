package titan.gun;

import java.awt.geom.Point2D;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;
import titan.utils.Geometry;

/** Mira linear: supõe que o inimigo segue em linha reta. */
public class LinearTargeting implements TargetingStrategy {
    @Override
    public double aimAngle(EnemyState enemy, double myX, double myY,
                           double bulletSpeed, BattlefieldBounds field) {
        Point2D.Double p = MotionPredictor.predict(
                enemy, myX, myY, bulletSpeed, field, 0);
        return Geometry.absoluteBearing(myX, myY, p.x, p.y);
    }
}
