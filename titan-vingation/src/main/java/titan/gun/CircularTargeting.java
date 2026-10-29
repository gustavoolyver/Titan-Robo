package titan.gun;

import java.awt.geom.Point2D;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;
import titan.utils.Geometry;

/**
 * Mira circular: supõe que o inimigo continua curvando na mesma taxa.
 * Ideia inspirada no SuperRamFire (Super Sample Robots do Robocode, CrazyBassoonist).
 */
public class CircularTargeting implements TargetingStrategy {
    @Override
    public double aimAngle(EnemyState enemy, double myX, double myY,
                           double bulletSpeed, BattlefieldBounds field) {
        Point2D.Double p = MotionPredictor.predict(
                enemy, myX, myY, bulletSpeed, field, enemy.headingChange);
        return Geometry.absoluteBearing(myX, myY, p.x, p.y);
    }
}
