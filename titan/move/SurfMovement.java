package titan.move;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import robocode.AdvancedRobot;
import robocode.Bullet;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.util.Utils;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;

/**
 * Movimento 1v1 com wave surfing simples.
 *
 * Quando o inimigo atira (queda de energia), cria-se uma onda. Antes de ela
 * chegar, o robô simula ir pelo sentido horário e pelo anti-horário e escolhe
 * o lado onde, historicamente, o inimigo mais raramente nos acertou.
 * Sem ondas no ar, só orbita mantendo a distância ideal.
 */
public class SurfMovement {
    private static final int BINS = 31;
    private static final int MIDDLE = (BINS - 1) / 2;
    private static final double MIN_BULLET_POWER = 0.1;
    private static final double MAX_BULLET_POWER = 3.0;
    private static final double MAX_ROBOT_SPEED = 8;
    private static final double WALL_STICK = 150;
    private static final int MAX_WALL_ATTEMPTS = 120;
    private static final int MAX_PREDICT_TICKS = 300;

    private static final double IDEAL_DISTANCE = 350;
    private static final double MAX_DISTANCE_TILT = 0.5;

    // static: o que aprendemos sobre o inimigo sobrevive entre rounds
    private static final Map<String, double[]> DANGER = new HashMap<String, double[]>();

    private final AdvancedRobot robot;
    private final BattleField field;
    private final List<EnemyWave> waves = new ArrayList<EnemyWave>();

    // +1 = sentido horário em volta do inimigo, -1 = anti-horário
    private int direction = 1;

    // dano que o NOSSO tiro causou no inimigo, para não confundir com um tiro dele
    private String hitVictim;
    private double hitDamage;

    // estado do scan anterior (o tiro aconteceu antes do scan em que vimos a queda)
    private boolean hasPrevious;
    private long previousTime;
    private double previousX;
    private double previousY;
    private double previousAbsBearing;
    private int previousLateralDirection = 1;

    public SurfMovement(AdvancedRobot robot, BattleField field) {
        this.robot = robot;
        this.field = field;
    }

    // ---------------------------------------------------------------- execução

    public void execute(Enemy enemy) {
        if (enemy == null) {
            return;
        }
        long now = robot.getTime();
        double myX = robot.getX();
        double myY = robot.getY();

        removePassedWaves(now, myX, myY);
        EnemyWave wave = closestWave(now, myX, myY);

        double goAngle;
        if (wave != null) {
            double dangerClockwise = checkDanger(wave, 1, now);
            double dangerCounter = checkDanger(wave, -1, now);
            if (dangerCounter < dangerClockwise) {
                direction = -1;
            } else if (dangerClockwise < dangerCounter) {
                direction = 1;
            }
            double tangent = Geometry.absoluteBearing(wave.sourceX, wave.sourceY, myX, myY)
                    + direction * Math.PI / 2;
            goAngle = wallSmoothing(myX, myY, tangent, direction);
        } else {
            double bearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
            double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
            double tilt = limit(-1, (distance - IDEAL_DISTANCE) / IDEAL_DISTANCE, 1)
                    * MAX_DISTANCE_TILT;
            double orbit = bearing - direction * (Math.PI / 2 - tilt);
            goAngle = wallSmoothing(myX, myY, orbit, direction);
        }
        goTo(goAngle);
    }

    private void goTo(double goAngle) {
        double turn = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
        double ahead = 100;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Utils.normalRelativeAngle(turn + Math.PI);
            ahead = -100;
        }
        robot.setTurnRightRadians(turn);
        robot.setAhead(ahead);
        robot.setMaxVelocity(MAX_ROBOT_SPEED);
    }

    // ----------------------------------------------------------------- eventos

    public void onScannedRobot(Enemy enemy) {
        double myX = robot.getX();
        double myY = robot.getY();
        double absBearing = Geometry.absoluteBearing(enemy.x, enemy.y, myX, myY);
        double lateral = robot.getVelocity() * Math.sin(robot.getHeadingRadians() - absBearing);
        int lateralDirection = lateral > 0 ? 1 : (lateral < 0 ? -1 : previousLateralDirection);

        double drop = enemy.energyDrop();
        if (enemy.name.equals(hitVictim)) {
            drop -= hitDamage;
            hitVictim = null;
            hitDamage = 0;
        }
        if (hasPrevious
                && drop >= MIN_BULLET_POWER && drop <= MAX_BULLET_POWER
                && enemy.lastSeen - previousTime <= 2) {
            waves.add(new EnemyWave(enemy.name, previousX, previousY,
                    previousAbsBearing, previousLateralDirection,
                    20 - 3 * drop, enemy.lastSeen - 1));
        }

        hasPrevious = true;
        previousTime = enemy.lastSeen;
        previousX = enemy.x;
        previousY = enemy.y;
        previousAbsBearing = absBearing;
        previousLateralDirection = lateralDirection;
    }

    public void onBulletHit(BulletHitEvent e) {
        double power = e.getBullet().getPower();
        hitVictim = e.getName();
        hitDamage = 4 * power + (power > 1 ? 2 * (power - 1) : 0);
    }

    /** Descobre de qual onda veio a bala e registra onde fomos acertados. */
    public void onHitByBullet(HitByBulletEvent e) {
        Bullet bullet = e.getBullet();
        long now = robot.getTime();
        EnemyWave match = null;
        double bestDiff = 50;
        for (EnemyWave wave : waves) {
            if (!wave.enemyName.equals(e.getName())
                    || Math.abs(wave.speed - bullet.getVelocity()) > 0.001) {
                continue;
            }
            double radius = (now - wave.fireTime) * wave.speed;
            double diff = Math.abs(
                    Point2D.distance(wave.sourceX, wave.sourceY, bullet.getX(), bullet.getY())
                            - radius);
            if (diff < bestDiff) {
                bestDiff = diff;
                match = wave;
            }
        }
        if (match != null) {
            logHit(match, bullet.getX(), bullet.getY());
            waves.remove(match);
        }
    }

    public void onHitWall() {
        direction = -direction;
    }

    // ------------------------------------------------------------------- ondas

    private void removePassedWaves(long now, double myX, double myY) {
        Iterator<EnemyWave> it = waves.iterator();
        while (it.hasNext()) {
            EnemyWave wave = it.next();
            double radius = (now - wave.fireTime) * wave.speed;
            if (radius > Point2D.distance(myX, myY, wave.sourceX, wave.sourceY) + 50) {
                it.remove();
            }
        }
    }

    /** A onda que vai nos alcançar primeiro. */
    private EnemyWave closestWave(long now, double myX, double myY) {
        EnemyWave closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (EnemyWave wave : waves) {
            double radius = (now - wave.fireTime) * wave.speed;
            double distance = Point2D.distance(myX, myY, wave.sourceX, wave.sourceY) - radius;
            if (distance > wave.speed && distance < closestDistance) {
                closest = wave;
                closestDistance = distance;
            }
        }
        return closest;
    }

    // ------------------------------------------------------------------ perigo

    private double checkDanger(EnemyWave wave, int dir, long now) {
        Point2D.Double position = predictPosition(wave, dir, now);
        return dangerFor(wave.enemyName)[factorIndex(wave, position.x, position.y)];
    }

    /** Simula (com a física do Robocode) nós seguindo na direção dir até a onda chegar. */
    private Point2D.Double predictPosition(EnemyWave wave, int dir, long now) {
        double x = robot.getX();
        double y = robot.getY();
        double heading = robot.getHeadingRadians();
        double velocity = robot.getVelocity();
        double radiusNow = (now - wave.fireTime) * wave.speed;

        int counter = 0;
        boolean intercepted = false;
        while (!intercepted && counter < MAX_PREDICT_TICKS) {
            double tangent = Geometry.absoluteBearing(wave.sourceX, wave.sourceY, x, y)
                    + dir * Math.PI / 2;
            double moveAngle = wallSmoothing(x, y, tangent, dir) - heading;
            double moveDir = 1;
            if (Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }
            moveAngle = Utils.normalRelativeAngle(moveAngle);

            double maxTurn = Math.PI / 720 * (40 - 3 * Math.abs(velocity));
            heading = Utils.normalRelativeAngle(heading + limit(-maxTurn, moveAngle, maxTurn));

            velocity += (velocity * moveDir < 0 ? 2 * moveDir : moveDir);
            velocity = limit(-MAX_ROBOT_SPEED, velocity, MAX_ROBOT_SPEED);

            x += Math.sin(heading) * velocity;
            y += Math.cos(heading) * velocity;
            counter++;

            if (Point2D.distance(x, y, wave.sourceX, wave.sourceY)
                    < radiusNow + counter * wave.speed + wave.speed) {
                intercepted = true;
            }
        }
        return new Point2D.Double(x, y);
    }

    /** Gira o ângulo para longe da parede até o ponto à frente caber no campo. */
    private double wallSmoothing(double x, double y, double angle, int orientation) {
        int attempts = 0;
        while (!field.contains(x + Math.sin(angle) * WALL_STICK, y + Math.cos(angle) * WALL_STICK)
                && attempts < MAX_WALL_ATTEMPTS) {
            angle += orientation * 0.05;
            attempts++;
        }
        return angle;
    }

    private void logHit(EnemyWave wave, double x, double y) {
        int index = factorIndex(wave, x, y);
        double[] danger = dangerFor(wave.enemyName);
        for (int i = 0; i < BINS; i++) {
            danger[i] += 1.0 / (1 + (i - index) * (i - index));
        }
    }

    /** Em qual bin (guess factor) da onda fica esse ponto. */
    private static int factorIndex(EnemyWave wave, double x, double y) {
        double offset = Utils.normalRelativeAngle(
                Geometry.absoluteBearing(wave.sourceX, wave.sourceY, x, y) - wave.refAngle);
        double maxEscape = Math.asin(MAX_ROBOT_SPEED / wave.speed);
        double factor = limit(-1, offset * wave.direction / maxEscape, 1);
        return (int) Math.round(MIDDLE + factor * MIDDLE);
    }

    private static double[] dangerFor(String name) {
        double[] danger = DANGER.get(name);
        if (danger == null) {
            danger = new double[BINS];
            DANGER.put(name, danger);
        }
        return danger;
    }

    private static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Onda de um tiro do inimigo. */
    private static final class EnemyWave {
        final String enemyName;
        final double sourceX;
        final double sourceY;
        final double refAngle;
        final int direction;
        final double speed;
        final long fireTime;

        EnemyWave(String enemyName, double sourceX, double sourceY, double refAngle,
                  int direction, double speed, long fireTime) {
            this.enemyName = enemyName;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.refAngle = refAngle;
            this.direction = direction;
            this.speed = speed;
            this.fireTime = fireTime;
        }
    }
}
