package titan.gun;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import robocode.util.Utils;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;

/**
 * Mira GuessFactor: a cada tiro cria uma "onda" e, quando ela alcança o inimigo,
 * registra para que lado e quanto ele escapou (de -1 a +1, onde +-1 é o escape
 * máximo possível). Na hora de mirar, aponta para o escape mais frequente
 * naquela situação (segmentada por distância e velocidade lateral).
 * Sem dados suficientes, usa a mira circular.
 */
public class GuessFactorTargeting implements Targeting {
    private static final int BINS = 31;
    private static final int MIDDLE = (BINS - 1) / 2;
    private static final int DISTANCE_SEGMENTS = 3;
    private static final int SPEED_SEGMENTS = 3;
    private static final double DECAY = 0.98;      // dados antigos perdem peso
    private static final double MIN_DATA = 3.0;    // abaixo disso usa o fallback
    private static final double MAX_WAVE_RADIUS = 2000;
    private static final double MAX_ROBOT_SPEED = 8;

    // static: os dados sobrevivem entre rounds da mesma batalha
    private static final Map<String, double[][]> STATS = new HashMap<String, double[][]>();

    private final List<Wave> waves = new ArrayList<Wave>();
    private final Targeting fallback = new CircularTargeting();

    @Override
    public double aimAngle(Enemy enemy, double myX, double myY,
                           double bulletSpeed, BattleField field) {
        double absBearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
        double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
        int direction = lateralDirection(enemy, absBearing);
        double[] bins = statsFor(enemy.name)[segment(distance, lateralSpeed(enemy, absBearing))];

        int best = -1;
        double bestValue = 0;
        double total = 0;
        for (int i = 0; i < BINS; i++) {
            total += bins[i];
            if (bins[i] > bestValue) {
                bestValue = bins[i];
                best = i;
            }
        }
        if (best < 0 || total < MIN_DATA) {
            return fallback.aimAngle(enemy, myX, myY, bulletSpeed, field);
        }
        double guessFactor = (best - MIDDLE) / (double) MIDDLE;
        return Utils.normalAbsoluteAngle(
                absBearing + direction * guessFactor * maxEscapeAngle(bulletSpeed));
    }

    @Override
    public void onFire(Enemy enemy, double myX, double myY,
                       double bulletSpeed, long time) {
        double absBearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
        double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
        waves.add(new Wave(enemy.name, myX, myY, absBearing,
                lateralDirection(enemy, absBearing), bulletSpeed, time,
                segment(distance, lateralSpeed(enemy, absBearing))));
    }

    @Override
    public void onTick(Enemy enemy, long time) {
        // só usa a posição do inimigo se ela for recente
        if (time - enemy.lastSeen > 1) {
            return;
        }
        Iterator<Wave> it = waves.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            if (!wave.enemyName.equals(enemy.name)) {
                continue;
            }
            double radius = (time - wave.fireTime) * wave.speed;
            double distance = Point2D.distance(wave.sourceX, wave.sourceY, enemy.x, enemy.y);
            if (radius >= distance) {
                record(wave, enemy);
                it.remove();
            } else if (radius > MAX_WAVE_RADIUS) {
                it.remove();
            }
        }
    }

    private void record(Wave wave, Enemy enemy) {
        double angle = Geometry.absoluteBearing(wave.sourceX, wave.sourceY, enemy.x, enemy.y);
        double offset = Utils.normalRelativeAngle(angle - wave.refAngle);
        double guessFactor = offset * wave.direction / maxEscapeAngle(wave.speed);
        guessFactor = Math.max(-1, Math.min(1, guessFactor));
        int index = (int) Math.round(MIDDLE + guessFactor * MIDDLE);

        double[] bins = statsFor(enemy.name)[wave.segment];
        for (int i = 0; i < BINS; i++) {
            bins[i] *= DECAY;
            // espalha o acerto pelos bins vizinhos (o alvo tem largura)
            bins[i] += 1.0 / (1 + (i - index) * (i - index));
        }
    }

    /** +1 se o inimigo se move no sentido horário em relação a nós, -1 no contrário. */
    private static int lateralDirection(Enemy enemy, double absBearing) {
        return enemy.velocity * Math.sin(enemy.heading - absBearing) >= 0 ? 1 : -1;
    }

    private static double lateralSpeed(Enemy enemy, double absBearing) {
        return Math.abs(enemy.velocity * Math.sin(enemy.heading - absBearing));
    }

    private static double maxEscapeAngle(double bulletSpeed) {
        return Math.asin(MAX_ROBOT_SPEED / bulletSpeed);
    }

    private static int segment(double distance, double lateralSpeed) {
        int d = distance < 250 ? 0 : (distance < 450 ? 1 : 2);
        int s = lateralSpeed < 2 ? 0 : (lateralSpeed < 6 ? 1 : 2);
        return d * SPEED_SEGMENTS + s;
    }

    private static double[][] statsFor(String name) {
        double[][] stats = STATS.get(name);
        if (stats == null) {
            stats = new double[DISTANCE_SEGMENTS * SPEED_SEGMENTS][BINS];
            STATS.put(name, stats);
        }
        return stats;
    }

    /** Onda de um tiro nosso. */
    private static final class Wave {
        final String enemyName;
        final double sourceX;
        final double sourceY;
        final double refAngle;
        final int direction;
        final double speed;
        final long fireTime;
        final int segment;

        Wave(String enemyName, double sourceX, double sourceY, double refAngle,
             int direction, double speed, long fireTime, int segment) {
            this.enemyName = enemyName;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.refAngle = refAngle;
            this.direction = direction;
            this.speed = speed;
            this.fireTime = fireTime;
            this.segment = segment;
        }
    }
}
