package titan.gun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import robocode.util.Utils;
import titan.utils.BattleField;
import titan.utils.Enemy;
import titan.utils.Geometry;
import titan.stats.LearningStore;
import robocode.AdvancedRobot;

/**
 * Várias miras em paralelo. A cada turno cada mira "atira" uma onda virtual;
 * quando a onda alcança o inimigo vemos quais miras teriam acertado. Para
 * atirar de verdade usamos a mira com maior taxa de acerto contra aquele
 * inimigo (ideia do VirtualGunsManager do Diamond, bem mais simples).
 *
 * Em caso de empate vale a primeira mira da lista.
 */
public class VirtualGuns implements Targeting {
    private static final double DECAY = 0.998;   // acertos antigos perdem peso devagar
    private static final double ROBOT_HALF_WIDTH = 18;
    private static final double MAX_WAVE_RADIUS = 2000;

    // static: o desempenho de cada mira sobrevive entre rounds da mesma batalha
    // scores[mira][0] = acertos (com decaimento), scores[mira][1] = tentativas
    private static final Map<String, double[][]> SCORES = new LinkedHashMap<String, double[][]>(16, 0.75f, true);

    private final BattleField field;
    private final Targeting[] guns;
    private final List<VirtualWave> waves = new ArrayList<VirtualWave>();

    public VirtualGuns(BattleField field, Targeting... guns) {
        this.field = field;
        this.guns = guns;
    }

    public static void load(AdvancedRobot robot) {
        Map<String, double[][]> loaded = LearningStore.load(robot, "VirtualGuns.dat", 3, 2);
        if (loaded != null) {
            SCORES.clear();
            SCORES.putAll(loaded);
        }
    }

    public static void save(AdvancedRobot robot) {
        LearningStore.save(robot, "VirtualGuns.dat", SCORES);
    }

    @Override
    public double aimAngle(Enemy enemy, double myX, double myY,
                           double bulletSpeed, BattleField fieldIgnored) {
        return guns[bestGun(enemy.name)].aimAngle(enemy, myX, myY, bulletSpeed, field);
    }

    @Override
    public void onAim(Enemy enemy, double myX, double myY,
                      double bulletSpeed, long time) {
        for (Targeting gun : guns) {
            gun.onAim(enemy, myX, myY, bulletSpeed, time);
        }
        if (time - enemy.lastSeen > 1) {
            return;
        }
        double[] aims = new double[guns.length];
        for (int i = 0; i < guns.length; i++) {
            aims[i] = guns[i].aimAngle(enemy, myX, myY, bulletSpeed, field);
        }
        waves.add(new VirtualWave(enemy.name, myX, myY, bulletSpeed, time, aims));
    }

    @Override
    public void onFire(Enemy enemy, double myX, double myY,
                       double bulletSpeed, long time) {
        for (Targeting gun : guns) {
            gun.onFire(enemy, myX, myY, bulletSpeed, time);
        }
    }

    @Override
    public void onTick(Enemy enemy, long time) {
        for (Targeting gun : guns) {
            gun.onTick(enemy, time);
        }
        if (time - enemy.lastSeen > 1) {
            return;
        }
        Iterator<VirtualWave> it = waves.iterator();
        while (it.hasNext()) {
            VirtualWave wave = it.next();
            double radius = (time - wave.fireTime) * wave.speed;
            if (radius > MAX_WAVE_RADIUS) {
                it.remove();
                continue;
            }
            if (!wave.enemyName.equals(enemy.name)) {
                continue;
            }
            double distance = Math.hypot(enemy.x - wave.sourceX, enemy.y - wave.sourceY);
            if (radius >= distance) {
                score(wave, enemy, distance);
                it.remove();
            } else if (radius > MAX_WAVE_RADIUS) {
                it.remove();
            }
        }
    }

    private void score(VirtualWave wave, Enemy enemy, double distance) {
        double angle = Geometry.absoluteBearing(wave.sourceX, wave.sourceY, enemy.x, enemy.y);
        double halfWidth = Math.atan(ROBOT_HALF_WIDTH / Math.max(distance, 1));
        double[][] scores = scoresFor(enemy.name);
        for (int i = 0; i < guns.length; i++) {
            scores[i][0] *= DECAY;
            scores[i][1] = scores[i][1] * DECAY + 1;
            if (Math.abs(Utils.normalRelativeAngle(angle - wave.aims[i])) <= halfWidth) {
                scores[i][0] += 1;
            }
        }
    }

    private int bestGun(String enemyName) {
        double[][] scores = scoresFor(enemyName);
        int best = 0;
        double bestRate = -1;
        for (int i = 0; i < guns.length; i++) {
            double rate = scores[i][1] > 0 ? scores[i][0] / scores[i][1] : 0;
            if (rate > bestRate + 1e-9) {
                bestRate = rate;
                best = i;
            }
        }
        return best;
    }

    private double[][] scoresFor(String enemyName) {
        double[][] scores = SCORES.get(enemyName);
        if (scores == null || scores.length != guns.length) {
            scores = new double[guns.length][2];
            SCORES.put(enemyName, scores);
        }
        return scores;
    }

    /** Onda virtual: guarda para onde cada mira apontaria naquele turno. */
    private static final class VirtualWave {
        final String enemyName;
        final double sourceX;
        final double sourceY;
        final double speed;
        final long fireTime;
        final double[] aims;

        VirtualWave(String enemyName, double sourceX, double sourceY,
                    double speed, long fireTime, double[] aims) {
            this.enemyName = enemyName;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.speed = speed;
            this.fireTime = fireTime;
            this.aims = aims;
        }
    }
}
