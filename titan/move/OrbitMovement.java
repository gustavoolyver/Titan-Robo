package titan.move;

import java.awt.geom.Point2D;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.util.Utils;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;

/** Movimento: orbita o alvo, mantém uma distância ideal e inverte quando ele parece ter atirado. */
public class OrbitMovement {
    private static final double STEP = 140;
    private static final double MIN_BULLET_POWER = 0.1;
    private static final double MAX_BULLET_POWER = 3.0;
    private static final int MAX_WALL_ATTEMPTS = 30;

    // distância que tentamos manter do alvo e o quanto podemos "inclinar" a órbita
    // para nos aproximar (alvo longe) ou afastar (alvo perto)
    private static final double IDEAL_DISTANCE = 350;
    private static final double MAX_DISTANCE_TILT = 0.5; // radianos (~29 graus)

    private final AdvancedRobot robot;
    private final BattleField field;
    private int direction = 1;

    // dano que o NOSSO tiro causou no inimigo, para não confundir com um tiro dele
    private String hitVictim;
    private double hitDamage;

    public OrbitMovement(AdvancedRobot robot, BattleField field) {
        this.robot = robot;
        this.field = field;
    }

    public void execute(Enemy enemy) {
        if (enemy == null) {
            return;
        }
        double myX = robot.getX();
        double myY = robot.getY();
        double bearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
        double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);

        // tilt > 0: alvo longe, inclina a órbita em direção a ele; tilt < 0: alvo perto, afasta
        double tilt = Math.max(-1, Math.min(1, (distance - IDEAL_DISTANCE) / IDEAL_DISTANCE))
                * MAX_DISTANCE_TILT;
        double orbitAngle = bearing + direction * (Math.PI / 2 - tilt);
        double destX = myX + Math.sin(orbitAngle) * STEP;
        double destY = myY + Math.cos(orbitAngle) * STEP;

        int attempts = 0;
        while (!field.contains(destX, destY) && attempts < MAX_WALL_ATTEMPTS) {
            orbitAngle += 0.1 * direction;
            destX = myX + Math.sin(orbitAngle) * STEP;
            destY = myY + Math.cos(orbitAngle) * STEP;
            attempts++;
        }

        double desired = Geometry.absoluteBearing(myX, myY, destX, destY);
        double turn = Utils.normalRelativeAngle(desired - robot.getHeadingRadians());
        double ahead = STEP;

        if (Math.abs(turn) > Math.PI / 2) {
            turn = Utils.normalRelativeAngle(turn + Math.PI);
            ahead = -STEP;
        }

        robot.setTurnRightRadians(turn);
        robot.setAhead(ahead);

        if (distance < 150) {
            robot.setMaxVelocity(5);
        } else if (distance < 350) {
            robot.setMaxVelocity(7);
        } else {
            robot.setMaxVelocity(8);
        }
    }

    public void onScannedRobot(Enemy enemy) {
        double drop = enemy.energyDrop();
        if (enemy.name.equals(hitVictim)) {
            drop -= hitDamage;
            hitVictim = null;
            hitDamage = 0;
        }
        if (drop >= MIN_BULLET_POWER && drop <= MAX_BULLET_POWER) {
            reverse();
        }
    }

    public void onBulletHit(BulletHitEvent e) {
        double power = e.getBullet().getPower();
        hitVictim = e.getName();
        hitDamage = 4 * power + (power > 1 ? 2 * (power - 1) : 0);
    }

    public void onHitByBullet() {
        reverse();
    }

    public void onHitWall() {
        reverse();
    }

    private void reverse() {
        direction = -direction;
    }
}
