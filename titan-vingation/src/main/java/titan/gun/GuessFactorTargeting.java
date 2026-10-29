package titan.gun;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import robocode.util.Utils;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;
import titan.utils.Geometry;
import titan.stats.LearningRepository;
import robocode.AdvancedRobot;

/**
 * Mira GuessFactor com tiros virtuais.
 *
 * A cada turno cria-se uma onda "virtual" (como se tivéssemos atirado agora).
 * Quando ela alcança o inimigo, registra para que lado e quanto ele escapou
 * (de -1 a +1, onde +-1 é o escape máximo possível). Como nascem ondas todo
 * turno, a mira aprende muito mais rápido do que só com os tiros reais.
 *
 * Na hora de mirar, aponta para o escape mais frequente na situação atual
 * (segmentada por distância e velocidade lateral). Se essa situação ainda tem
 * poucos dados usa o histórico geral; sem dados nenhum, usa a mira circular.
 */
public class GuessFactorTargeting implements TargetingStrategy {
    private static final int BINS = 31;
    private static final int MIDDLE = (BINS - 1) / 2;
    private static final int DISTANCE_SEGMENTS = 3;
    private static final int SPEED_SEGMENTS = 6;
    // limites (px/tick) entre as faixas de velocidade lateral do inimigo
    private static final double[] SPEED_LIMITS = {1, 2.5, 4, 5.5, 7};
    private static final int SEGMENTS = DISTANCE_SEGMENTS * SPEED_SEGMENTS;
    private static final int GLOBAL_ROW = SEGMENTS; // última linha = histórico geral

    private static final double DECAY = 0.995;         // dados antigos perdem peso devagar
    private static final double MIN_SEGMENT_DATA = 30; // peso mínimo para confiar no segmento
    private static final double MIN_GLOBAL_DATA = 15;  // abaixo disso usa o fallback
    private static final double MAX_WAVE_RADIUS = 2000;
    private static final double MAX_ROBOT_SPEED = 8;

    // static: os dados sobrevivem entre rounds da mesma batalha
    private static final Map<String, double[][]> STATS = new LinkedHashMap<String, double[][]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, double[][]> entry) {
            return size() > 12;
        }
    };

    private final List<Wave> waves = new ArrayList<Wave>();
    private final TargetingStrategy fallback = new CircularTargeting();

    public static void load(AdvancedRobot robot) {
        Map<String, double[][]> loaded = LearningRepository.load(robot, "GuessFactorTargeting.dat", SEGMENTS + 1, BINS);
        if (loaded != null) {
            STATS.clear();
            STATS.putAll(loaded);
        }
    }

    public static void save(AdvancedRobot robot) {
        LearningRepository.save(robot, "GuessFactorTargeting.dat", STATS);
    }

    @Override
    public double aimAngle(EnemyState enemy, double myX, double myY,
                           double bulletSpeed, BattlefieldBounds field) {
        double absBearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
        double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
        int direction = lateralDirection(enemy, absBearing);

        double[][] stats = statsFor(enemy.name);
        double[] bins = stats[segment(distance, lateralSpeed(enemy, absBearing))];
        if (total(bins) < MIN_SEGMENT_DATA) {
            bins = stats[GLOBAL_ROW];
            if (total(bins) < MIN_GLOBAL_DATA) {
                return fallback.aimAngle(enemy, myX, myY, bulletSpeed, field);
            }
        }

        int best = 0;
        for (int i = 1; i < BINS; i++) {
            if (bins[i] > bins[best]) {
                best = i;
            }
        }
        double guessFactor = (best - MIDDLE) / (double) MIDDLE;
        return Utils.normalAbsoluteAngle(
                absBearing + direction * guessFactor * maxEscapeAngle(bulletSpeed));
    }

    /** Todo turno: cria o tiro virtual com a potência que usaríamos agora. */
    @Override
    public void onAim(EnemyState enemy, double myX, double myY,
                      double bulletSpeed, long time) {
        // só vale se a posição do inimigo for recente
        if (time - enemy.lastSeen > 1) {
            return;
        }
        double absBearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
        double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
        waves.add(new Wave(enemy.name, myX, myY, absBearing,
                lateralDirection(enemy, absBearing), bulletSpeed, time,
                segment(distance, lateralSpeed(enemy, absBearing))));
    }

    @Override
    public void onTick(EnemyState enemy, long time) {
        Iterator<Wave> it = waves.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            double radius = (time - wave.fireTime) * wave.speed;
            if (radius > MAX_WAVE_RADIUS) {
                it.remove();
                continue;
            }
            if (time - enemy.lastSeen > 1 || !wave.enemyName.equals(enemy.name)) {
                continue;
            }
            double distance = Point2D.distance(wave.sourceX, wave.sourceY, enemy.x, enemy.y);
            if (radius >= distance) {
                record(wave, enemy);
                it.remove();
            }
        }
    }

    private void record(Wave wave, EnemyState enemy) {
        double angle = Geometry.absoluteBearing(wave.sourceX, wave.sourceY, enemy.x, enemy.y);
        double offset = Utils.normalRelativeAngle(angle - wave.refAngle);
        double guessFactor = offset * wave.direction / maxEscapeAngle(wave.speed);
        guessFactor = Math.max(-1, Math.min(1, guessFactor));
        int index = (int) Math.round(MIDDLE + guessFactor * MIDDLE);

        double[][] stats = statsFor(enemy.name);
        add(stats[wave.segment], index);
        add(stats[GLOBAL_ROW], index);
    }

    private static void add(double[] bins, int index) {
        for (int i = 0; i < BINS; i++) {
            bins[i] *= DECAY;
            // espalha o acerto pelos bins vizinhos (o alvo tem largura)
            bins[i] += 1.0 / (1 + (i - index) * (i - index));
        }
    }

    private static double total(double[] bins) {
        double sum = 0;
        for (int i = 0; i < BINS; i++) {
            sum += bins[i];
        }
        return sum;
    }

    /** +1 se o inimigo se move no sentido horário em relação a nós, -1 no contrário. */
    private static int lateralDirection(EnemyState enemy, double absBearing) {
        return enemy.velocity * Math.sin(enemy.heading - absBearing) >= 0 ? 1 : -1;
    }

    private static double lateralSpeed(EnemyState enemy, double absBearing) {
        return Math.abs(enemy.velocity * Math.sin(enemy.heading - absBearing));
    }

    private static double maxEscapeAngle(double bulletSpeed) {
        return Math.asin(MAX_ROBOT_SPEED / bulletSpeed);
    }

    private static int segment(double distance, double lateralSpeed) {
        int d = distance < 250 ? 0 : (distance < 450 ? 1 : 2);
        int s = 0;
        while (s < SPEED_SEGMENTS - 1 && lateralSpeed >= SPEED_LIMITS[s]) {
            s++;
        }
        return d * SPEED_SEGMENTS + s;
    }

    private static double[][] statsFor(String name) {
        double[][] stats = STATS.get(name);
        if (stats == null) {
            stats = new double[SEGMENTS + 1][BINS];
            STATS.put(name, stats);
        }
        return stats;
    }

    /** Onda de um tiro (real ou virtual) nosso. */
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
