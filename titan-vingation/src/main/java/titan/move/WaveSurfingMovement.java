package titan.move;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import robocode.AdvancedRobot;
import robocode.Bullet;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.util.Utils;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;
import titan.utils.Geometry;
import titan.stats.LearningRepository;

/**
 * Movimento 1v1 com wave surfing.
 *
 * Quando o inimigo atira (queda de energia), cria-se uma onda. O robô simula
 * seguir pelo sentido horário e pelo anti-horário considerando as DUAS ondas
 * mais próximas, e escolhe o lado com menor perigo.
 *
 * O perigo vem de dois perfis aprendidos sobre o inimigo:
 *  - onde fomos acertados (separado por distância e velocidade nossa);
 *  - por onde passamos em todas as ondas, acertando ou não. Esse perfil só
 *    pesa quando o inimigo está acertando bastante, e faz o robô "achatar"
 *    o próprio padrão, para uma mira estatística não achar um pico.
 * Sem ondas no ar, só orbita mantendo a distância ideal.
 */
public class WaveSurfingMovement {
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

    // segmentação do perfil de acertos: distância do inimigo x nossa velocidade lateral
    private static final int DISTANCE_SEGMENTS = 3;
    private static final int SPEED_SEGMENTS = 3;
    private static final int SEGMENTS = DISTANCE_SEGMENTS * SPEED_SEGMENTS;
    private static final int GLOBAL_ROW = SEGMENTS; // última linha = todos os acertos
    private static final double GLOBAL_WEIGHT = 0.3;

    // quantas ondas considerar na simulação e o peso de cada uma
    private static final double[] WAVE_WEIGHTS = {1.0, 0.5};

    private static final double HIT_DECAY = 0.98;
    private static final double VISIT_DECAY = 0.99;
    private static final double RATE_DECAY = 0.98;
    private static final double FLATTEN_START_RATE = 0.06; // taxa de acerto do inimigo
    private static final double FLATTEN_FULL_RATE = 0.18;
    private static final double FLATTEN_MAX = 0.6;

    // static: o que aprendemos sobre o inimigo sobrevive entre rounds
    private static final Map<String, Profile> PROFILES = new LinkedHashMap<String, Profile>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Profile> entry) {
            return size() > 12;
        }
    };

    private final AdvancedRobot robot;
    private final BattlefieldBounds field;
    private final List<EnemyWave> waves = new ArrayList<EnemyWave>();

    // +1 = sentido horário em volta do inimigo, -1 = anti-horário
    private int direction = 1;

    // Cada adversario tem seu proprio scan e dano pendente.
    private final Map<String, ScanState> scans = new HashMap<String, ScanState>();
    private final Map<String, Double> ownDamage = new HashMap<String, Double>();

    private static final class ScanState {
        long time;
        double x, y, bearing;
        int direction, segment;
    }

    public static void load(AdvancedRobot robot) {
        Map<String, double[][]> data = LearningRepository.load(robot,
                "SurfMovement.dat", SEGMENTS + 3, BINS);
        if (data == null) return;
        PROFILES.clear();
        for (Map.Entry<String, double[][]> entry : data.entrySet()) {
            Profile profile = new Profile();
            double[][] rows = entry.getValue();
            for (int i = 0; i <= SEGMENTS; i++) {
                System.arraycopy(rows[i], 0, profile.hits[i], 0, BINS);
            }
            System.arraycopy(rows[SEGMENTS + 1], 0, profile.visits, 0, BINS);
            profile.hitCount = rows[SEGMENTS + 2][0];
            profile.breakCount = rows[SEGMENTS + 2][1];
            PROFILES.put(entry.getKey(), profile);
        }
    }

    public static void save(AdvancedRobot robot) {
        Map<String, double[][]> data = new LinkedHashMap<String, double[][]>();
        for (Map.Entry<String, Profile> entry : PROFILES.entrySet()) {
            Profile profile = entry.getValue();
            double[][] rows = new double[SEGMENTS + 3][BINS];
            for (int i = 0; i <= SEGMENTS; i++) {
                System.arraycopy(profile.hits[i], 0, rows[i], 0, BINS);
            }
            System.arraycopy(profile.visits, 0, rows[SEGMENTS + 1], 0, BINS);
            rows[SEGMENTS + 2][0] = profile.hitCount;
            rows[SEGMENTS + 2][1] = profile.breakCount;
            data.put(entry.getKey(), rows);
        }
        LearningRepository.save(robot, "SurfMovement.dat", data);
    }

    public WaveSurfingMovement(AdvancedRobot robot, BattlefieldBounds field) {
        this.robot = robot;
        this.field = field;
    }

    // ---------------------------------------------------------------- execução

    public void execute(EnemyState enemy) {
        long now = robot.getTime();
        double myX = robot.getX();
        double myY = robot.getY();

        updateWaves(now, myX, myY);
        if (enemy == null) return;
        final List<EnemyWave> targets = closestWaves(now, myX, myY, WAVE_WEIGHTS.length);

        double goAngle;
        if (!targets.isEmpty()) {
            List<double[]> profiles = new ArrayList<double[]>();
            for (EnemyWave wave : targets) {
                profiles.add(dangerProfile(wave));
            }
            double dangerClockwise = predictDanger(1, targets, profiles, now);
            double dangerCounter = predictDanger(-1, targets, profiles, now);
            if (dangerCounter < dangerClockwise) {
                direction = -1;
            } else if (dangerClockwise < dangerCounter) {
                direction = 1;
            }
            EnemyWave first = targets.get(0);
            double tangent = Geometry.absoluteBearing(first.sourceX, first.sourceY, myX, myY)
                    + direction * Math.PI / 2;
            goAngle = wallSmoothing(myX, myY, tangent, direction);
        } else {
            double bearing = Geometry.absoluteBearing(myX, myY, enemy.x, enemy.y);
            double distance = Point2D.distance(myX, myY, enemy.x, enemy.y);
            double desiredDistance = enemy.energy < 0.1 ? 120 : IDEAL_DISTANCE;
            double tilt = limit(-1, (distance - desiredDistance) / desiredDistance, 1)
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

    public void onScannedRobot(EnemyState enemy) {
        double myX = robot.getX();
        double myY = robot.getY();
        double absBearing = Geometry.absoluteBearing(enemy.x, enemy.y, myX, myY);
        double lateral = robot.getVelocity() * Math.sin(robot.getHeadingRadians() - absBearing);
        ScanState previous = scans.get(enemy.name);
        int oldDirection = previous == null ? 1 : previous.direction;
        int lateralDirection = lateral > 0 ? 1 : (lateral < 0 ? -1 : oldDirection);

        double drop = enemy.energyDrop();
        Double damage = ownDamage.remove(enemy.name);
        if (damage != null) drop -= damage;
        // Scans consecutivos permitem posicionar a origem do tiro com precisao.
        if (previous != null && enemy.lastSeen - previous.time == 1
                && drop >= MIN_BULLET_POWER - 0.000001
                && drop <= MAX_BULLET_POWER + 0.000001) {
            double power = limit(MIN_BULLET_POWER, drop, MAX_BULLET_POWER);
            waves.add(new EnemyWave(enemy.name, previous.x, previous.y,
                    previous.bearing, previous.direction,
                    20 - 3 * power, previous.time, previous.segment));
            direction = -direction;
        }
        ScanState current = new ScanState();
        current.time = enemy.lastSeen;
        current.x = enemy.x;
        current.y = enemy.y;
        current.bearing = absBearing;
        current.direction = lateralDirection;
        current.segment = segment(Point2D.distance(myX, myY, enemy.x, enemy.y),
                Math.abs(lateral));
        scans.put(enemy.name, current);
    }

    public void onBulletHit(BulletHitEvent e) {
        double power = e.getBullet().getPower();
        double damage = 4 * power + (power > 1 ? 2 * (power - 1) : 0);
        Double pending = ownDamage.get(e.getName());
        ownDamage.put(e.getName(), damage + (pending == null ? 0 : pending));
    }

    /** Descobre de qual onda veio a bala e registra onde fomos acertados. */
    public void onHitByBullet(HitByBulletEvent e) {
        Bullet bullet = e.getBullet();
        Double pending = ownDamage.get(e.getName());
        ownDamage.put(e.getName(), (pending == null ? 0 : pending) - 3 * bullet.getPower());
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
            if (!match.broken) {
                match.broken = true;
                registerBreak(match, bullet.getX(), bullet.getY());
            }
            registerHit(match, bullet.getX(), bullet.getY());
            waves.remove(match);
        }
    }

    public void onHitWall() {
        direction = -direction;
    }

    // ------------------------------------------------------------------- ondas

    /** Registra as ondas que passaram por nós e descarta as que já ficaram para trás. */
    private void updateWaves(long now, double myX, double myY) {
        Iterator<EnemyWave> it = waves.iterator();
        while (it.hasNext()) {
            EnemyWave wave = it.next();
            double radius = (now - wave.fireTime) * wave.speed;
            double distance = Point2D.distance(myX, myY, wave.sourceX, wave.sourceY);
            if (!wave.broken && radius >= distance) {
                wave.broken = true;
                registerBreak(wave, myX, myY);
            }
            if (radius > distance + 50) {
                it.remove();
            }
        }
    }

    /** As ondas que vão nos alcançar primeiro, da mais próxima para a mais distante. */
    private List<EnemyWave> closestWaves(final long now, final double myX, final double myY,
                                         int max) {
        List<EnemyWave> candidates = new ArrayList<EnemyWave>();
        for (EnemyWave wave : waves) {
            if (timeToImpact(wave, now, myX, myY) > wave.speed) {
                candidates.add(wave);
            }
        }
        Collections.sort(candidates, new Comparator<EnemyWave>() {
            @Override
            public int compare(EnemyWave a, EnemyWave b) {
                return Double.compare(timeToImpact(a, now, myX, myY),
                        timeToImpact(b, now, myX, myY));
            }
        });
        return candidates.size() > max ? new ArrayList<EnemyWave>(candidates.subList(0, max))
                : candidates;
    }

    /** Distância que a onda ainda precisa percorrer até a nossa posição. */
    private static double timeToImpact(EnemyWave wave, long now, double myX, double myY) {
        double radius = (now - wave.fireTime) * wave.speed;
        return Point2D.distance(myX, myY, wave.sourceX, wave.sourceY) - radius;
    }

    // ------------------------------------------------------------------ perigo

    /**
     * Simula (com a física do Robocode) nós seguindo na direção dir e soma o
     * perigo no ponto onde cada onda nos alcança.
     */
    private double predictDanger(int dir, List<EnemyWave> targets, List<double[]> profiles,
                                 long now) {
        double x = robot.getX();
        double y = robot.getY();
        double heading = robot.getHeadingRadians();
        double velocity = robot.getVelocity();
        int elapsed = 0;
        double total = 0;

        for (int k = 0; k < targets.size(); k++) {
            EnemyWave wave = targets.get(k);
            double radiusNow = (now - wave.fireTime) * wave.speed;
            while (elapsed < MAX_PREDICT_TICKS && !reached(wave, radiusNow, elapsed, x, y)) {
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
                heading = Utils.normalRelativeAngle(
                        heading + limit(-maxTurn, moveAngle, maxTurn));

                velocity += (velocity * moveDir < 0 ? 2 * moveDir : moveDir);
                velocity = limit(-MAX_ROBOT_SPEED, velocity, MAX_ROBOT_SPEED);

                double nextX = x + Math.sin(heading) * velocity;
                double nextY = y + Math.cos(heading) * velocity;
                if (!field.contains(nextX, nextY)) {
                    // Penalize paths that hit a wall instead of simulating travel outside the arena.
                    total += 1;
                    velocity = 0;
                }
                x = field.clampX(nextX);
                y = field.clampY(nextY);
                elapsed++;
            }
            double weight = k < WAVE_WEIGHTS.length ? WAVE_WEIGHTS[k] : 0;
            total += weight * profiles.get(k)[factorIndex(wave, x, y)];
        }
        return total;
    }

    private static boolean reached(EnemyWave wave, double radiusNow, int elapsed,
                                   double x, double y) {
        return Point2D.distance(x, y, wave.sourceX, wave.sourceY)
                < radiusNow + elapsed * wave.speed + wave.speed;
    }

    /** Gira o ângulo para longe da parede até o ponto à frente caber no campo. */
    private double wallSmoothing(double x, double y, double angle, int orientation) {
        int attempts = 0;
        while (!field.contains(x + Math.sin(angle) * WALL_STICK, y + Math.cos(angle) * WALL_STICK)
                && attempts < MAX_WALL_ATTEMPTS) {
            angle += orientation * 0.05;
            attempts++;
        }
        if (!field.contains(x + Math.sin(angle) * WALL_STICK,
                            y + Math.cos(angle) * WALL_STICK)) {
            return Geometry.absoluteBearing(x, y, robot.getBattleFieldWidth() / 2,
                    robot.getBattleFieldHeight() / 2);
        }
        return angle;
    }

    /** Perigo de cada bin da onda: mistura do perfil de acertos com o de passagens. */
    private double[] dangerProfile(EnemyWave wave) {
        Profile profile = profileFor(wave.enemyName);
        double[] segment = profile.hits[wave.segment];
        double[] global = profile.hits[GLOBAL_ROW];

        double[] hits = new double[BINS];
        double hitsTotal = 0;
        double visitsTotal = 0;
        for (int i = 0; i < BINS; i++) {
            hits[i] = segment[i] + GLOBAL_WEIGHT * global[i];
            hitsTotal += hits[i];
            visitsTotal += profile.visits[i];
        }

        double flatten = flattenFactor(profile);
        double[] danger = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            double hitPart = hitsTotal > 0 ? hits[i] / hitsTotal : 0;
            double visitPart = visitsTotal > 0 ? profile.visits[i] / visitsTotal : 0;
            danger[i] = (1 - flatten) * hitPart + flatten * visitPart;
        }
        return danger;
    }

    /** 0 = só desvia dos acertos; sobe até FLATTEN_MAX quanto mais o inimigo acerta. */
    private static double flattenFactor(Profile profile) {
        if (profile.breakCount < 5) {
            return 0;
        }
        double rate = profile.hitCount / profile.breakCount;
        double t = limit(0, (rate - FLATTEN_START_RATE)
                / (FLATTEN_FULL_RATE - FLATTEN_START_RATE), 1);
        return t * FLATTEN_MAX;
    }

    // ---------------------------------------------------------------- registro

    /** Uma onda nos alcançou (acertando ou não): anota por onde passamos. */
    private void registerBreak(EnemyWave wave, double x, double y) {
        Profile profile = profileFor(wave.enemyName);
        profile.hitCount *= RATE_DECAY;
        profile.breakCount = profile.breakCount * RATE_DECAY + 1;
        addKernel(profile.visits, factorIndex(wave, x, y), VISIT_DECAY);
    }

    /** A bala dessa onda nos acertou nesse ponto. */
    private void registerHit(EnemyWave wave, double x, double y) {
        Profile profile = profileFor(wave.enemyName);
        profile.hitCount += 1;
        int index = factorIndex(wave, x, y);
        addKernel(profile.hits[wave.segment], index, HIT_DECAY);
        addKernel(profile.hits[GLOBAL_ROW], index, HIT_DECAY);
    }

    private static void addKernel(double[] bins, int index, double decay) {
        for (int i = 0; i < BINS; i++) {
            bins[i] *= decay;
            bins[i] += 1.0 / (1 + (i - index) * (i - index));
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

    private static int segment(double distance, double lateralSpeed) {
        int d = distance < 250 ? 0 : (distance < 450 ? 1 : 2);
        int s = lateralSpeed < 2 ? 0 : (lateralSpeed < 5.5 ? 1 : 2);
        return d * SPEED_SEGMENTS + s;
    }

    private static Profile profileFor(String name) {
        Profile profile = PROFILES.get(name);
        if (profile == null) {
            profile = new Profile();
            PROFILES.put(name, profile);
        }
        return profile;
    }

    private static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** O que aprendemos sobre a mira de um inimigo. */
    private static final class Profile {
        final double[][] hits = new double[SEGMENTS + 1][BINS];
        final double[] visits = new double[BINS];
        double hitCount;
        double breakCount;
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
        final int segment;
        boolean broken; // já alcançou nossa posição

        EnemyWave(String enemyName, double sourceX, double sourceY, double refAngle,
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
