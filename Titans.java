package titan;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * TitanVingation: Robocode CLASSICO, Java 8+, um unico fonte.
 * Projetado para melee (voce + 5 inimigos), com transicao automatica para duelo.
 * Instale em robots/titan/TitanVingation.java. Nao serve para Tank Royale.
 *
 * Sistemas:
 * - radar 360 em melee / trava com overscan em duelo;
 * - movimento minimum-risk com simulacao fisica e wall smoothing;
 * - wave surfing: ondas confiaveis em duelo, estimativas conservadoras em melee;
 * - seis miras virtuais: frontal, linear, circular, GF, aceleracao e kNN;
 * - anticercamento, colisao prevista e escape quando nao ha deslocamento;
 * - cobertura parcial por adversarios, considerando movimento e ondas ja em voo;
 * - baixa energia: alvo oportunista, reserva defensiva e tiros com ganho esperado;
 * - classificacao de perseguidores/orbitadores/evasivos e calibracao de tiros reais;
 * - cobertura exige rotas de saida; tres erros de recuperacao suspendem a aposta;
 * - foco inimigo e inferido; nao ha acesso a mira interna dos adversarios;
 * - flattener adaptativo e simulacao de planos de reversao no surfing;
 * - escolha de alvo, controle de potencia, protecao contra tiro bloqueado;
 * - aprendizado ONLINE: cada onda atualiza as miras durante o round;
 * - checkpoints a cada 500 ticks e ao encerrar cada round;
 * - snapshots titan-learning-v2-a.bin / -b.bin, GZIP com checksum;
 * - importa automaticamente a memoria v1 desta versao de arquivo unico.
 *
 * Referencias conceituais fornecidas: TitanV3 (ZIP) e ChimpazeComSarna.
 * O modelo especifico do Kader nao e aplicado a adversarios genericos.
 * Todos os auxiliares sao classes internas: este e o UNICO fonte necessario.
 *
 * Aprendizado estatistico, nao rede neural; nao garante vencer qualquer robo.
 * O arquivo de aprendizado fica no diretorio de dados do robo, fora do JAR.
 */
public class Titans extends AdvancedRobot {
    private static final double PI = Math.PI, TWO_PI = 2 * PI;
    private static final int BINS = 31, MID = BINS / 2, SEGMENTS = 18;
    private static final int MAX_MODELS = 12, MAX_WAVES = 100;
    private static final int GUNS = 6, FEATURES = 7, MAX_SAMPLES = 384, NEIGHBORS = 24;
    private static final int CHECKPOINT_TICKS = 500, MAX_SNAPSHOT_BYTES = 58000;
    private static final double[] FEATURE_WEIGHTS = {1.5, 2, 1, 1, 1, .8, .8};
    private static final String DATA = "titan-learning-v1.bin";
    private static final int MAGIC = 0x54564D31, MAGIC_V2 = 0x54564D32;
    private static final String SNAP_A = "titan-learning-v2-a.bin", SNAP_B = "titan-learning-v2-b.bin";
    private static long generation, roundsTotal;
    private static final boolean DEBUG_PAINT = false;
    private static final LinkedHashMap<String, Model> MEMORY =
        new LinkedHashMap<String, Model>(20, .75f, true);
    private static boolean loaded;

    private final Map<String, Enemy> enemies = new HashMap<String, Enemy>();
    private final List<Wave> incoming = new ArrayList<Wave>();
    private final List<GunWave> outgoing = new ArrayList<GunWave>();
    private final Point2D.Double[] selfHistory = new Point2D.Double[32];
    private final double[] velocityHistory = new double[32];
    private final double[] headingHistory = new double[32];
    private final long[] historyTime = new long[32];
    private Enemy target;
    private double width, height;
    private Point2D.Double destination, previousDestination;
    private int orbit = 1, skipped;
    private long lastPlan = -100, lastScan = -100, lastVirtual = -100;
    private int robotContacts, wallContacts;
    private boolean roundEnded;
    private boolean healing;
    private double health = 100, imminentDamage;
    private int focusedAttackers, healingEntries, recoveryShots, coveredPlans;
    private Enemy recoveryTarget;
    private boolean dirty;
    private long now;
    private long lastCheckpoint, escapeUntil = -1, lastEscapePlan = -100;
    private long lastContact = -100, lastMobility = -1;
    private int blockedTicks, contactStreak, recoveries, shots, hits, receivedHits, checkpoints;
    private boolean wantsMotion;
    private Point2D.Double lastPosition, escapeGoal, contactPoint;
    private double commandAngle, commandSpeed;
    private final Map<Bullet, Shot> realShots = new HashMap<Bullet, Shot>();

    @Override public void run() {
        width = getBattleFieldWidth(); height = getBattleFieldHeight();
        Arrays.fill(historyTime, -1000);
        setBodyColor(new Color(15, 28, 48));
        setGunColor(new Color(0, 200, 255));
        setRadarColor(new Color(85, 255, 185));
        setBulletColor(new Color(0, 220, 255));
        setScanColor(new Color(80, 255, 170));
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        loadLearning();
        for (Model model : MEMORY.values()) { model.retreatUntil = 0; model.recoveryMisses = 0; }
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        while (true) {
            now = getTime();
            rememberSelf();
            updateWaves();
            updateTactics();
            selectTarget();
            controlRadar();
            move();
            aimAndFire();
            if (checkpoint == null && dirty && now - lastCheckpoint >= CHECKPOINT_TICKS) saveLearning(false);
            checkpointTick();
            execute();
        }
    }

    private Point2D.Double me() { return new Point2D.Double(getX(), getY()); }
    private void rememberSelf() {
        int k = (int)(now % selfHistory.length);
        selfHistory[k] = me(); velocityHistory[k] = getVelocity();
        headingHistory[k] = getHeadingRadians(); historyTime[k] = now;
    }

    @Override public void onScannedRobot(ScannedRobotEvent e) {
        now = getTime(); lastScan = now;
        Point2D.Double self = me();
        double abs = getHeadingRadians() + e.getBearingRadians();
        Enemy en = enemies.get(e.getName());
        if (en == null) {
            en = new Enemy(e.getName(), model(e.getName()));
            enemies.put(e.getName(), en);
        }
        long gap = now - en.time;
        en.previousPoint = copy(en.p); en.previousTime = en.time;
        double oldEnergy = en.energy;
        Point2D.Double oldPoint = en.p;
        if (en.time >= 0 && gap > 0) {
            double drop = oldEnergy - e.getEnergy() - en.damage + en.bonus;
            boolean duel = getOthers() == 1;
            boolean validGap = gap <= (duel ? 2 : 12);
            if (validGap && drop >= .099 && drop <= 3.001
                    && now >= en.nextPossibleFire && incoming.size() < MAX_WAVES) {
                // Em melee outros tiros/colisoes tambem alteram energia.
                // Por isso a onda e uma HIPOTESE de baixa confianca.
                long fireTime = duel ? now - 1 : now - Math.max(1, gap / 2);
                Point2D.Double origin = oldPoint;
                int h = (int)(fireTime % selfHistory.length);
                Point2D.Double victim = historyTime[h] == fireTime ? selfHistory[h] : self;
                double vh = historyTime[h] == fireTime ? headingHistory[h] : getHeadingRadians();
                double vv = historyTime[h] == fireTime ? velocityHistory[h] : getVelocity();
                Wave w = new Wave();
                w.origin = copy(origin); w.time = fireTime; w.speed = 20 - 3 * drop;
                w.power = drop; w.bearing = bearing(origin, victim);
                w.direction = sign(vv * Math.sin(vh - w.bearing));
                w.segment = segment(origin.distance(victim), Math.abs(vv), wallDistance(victim));
                w.model = en.model; w.name = en.name;
                w.confidence = duel && gap == 1 ? 1.0 : (duel ? .7 : .20);
                w.model.enemyWaves += w.confidence;
                dirty = true;
                incoming.add(w);
                en.nextPossibleFire = fireTime + (long)Math.ceil((1 + drop / 5) / getGunCoolingRate());
            }
        }
        double deltaHeading = en.time >= 0 && gap > 0
            ? Utils.normalRelativeAngle(e.getHeadingRadians() - en.heading) / gap : 0;
        en.turn = clamp(-.16, deltaHeading, .16);
        double approach = e.getVelocity() * Math.cos(e.getHeadingRadians() - (abs + PI));
        double lateralMotion = Math.abs(e.getVelocity() * Math.sin(e.getHeadingRadians() - abs));
        en.chase = .94 * en.chase + .06 * (approach > 4 && e.getDistance() < 350 ? 1 : 0);
        en.orbiting = .94 * en.orbiting + .06 * (lateralMotion > 4 && Math.abs(approach) < 3 ? 1 : 0);
        en.evasive = .94 * en.evasive + .06 * (Math.abs(deltaHeading) > .075 ? 1 : 0);
        en.acceleration = en.time >= 0 && gap > 0
            ? clamp(-2, (Math.abs(e.getVelocity()) - Math.abs(en.velocity)) / gap, 1) : 0;
        en.p = project(self, abs, e.getDistance());
        en.collisionClock = -1;
        en.heading = e.getHeadingRadians(); en.velocity = e.getVelocity();
        en.energy = e.getEnergy(); en.time = now; en.dead = false;
        en.damage = en.bonus = 0;
        double lat = en.velocity * Math.sin(en.heading - abs);
        if (Math.abs(lat) > .05) {
            int direction = sign(lat);
            if (direction != en.direction) { en.directionChanged = now; en.evasive = Math.min(1, en.evasive + .15); }
            en.direction = direction;
        }
        updateGunWaves(en);
    }

    /** Nao podemos ler a mira interna dos inimigos: foco e uma estimativa. */
    private double desiredRange() {
        if (target == null) return 380;
        if (target.chase > .45) return 460;
        if (target.evasive > .40) return 310;
        if (target.orbiting > .50) return 335;
        return 380;
    }

    /** Conta saidas fisicamente livres, inclusive se o escudo deixar de existir. */
    private int escapeOptions(Point2D.Double p) {
        int count = 0;
        for (int i = 0; i < 8; i++) {
            Point2D.Double end = project(p, i * PI / 4, 100);
            if (!inside(end, 35)) continue;
            boolean free = true;
            for (Enemy e : enemies.values()) {
                if (e.dead || now - e.time > 16) continue;
                Point2D.Double obstacle = enemyPoint(e, 8);
                if (new Rectangle2D.Double(obstacle.x - 42, obstacle.y - 42, 84, 84)
                        .intersectsLine(p.x, p.y, end.x, end.y)) { free = false; break; }
            }
            if (free) count++;
        }
        return count;
    }

    /** Evidencia observavel de direcao da bala; scans nao revelam a mira adversaria. */
    private void observeRay(Bullet bullet) {
        Enemy shooter = enemies.get(bullet.getName());
        if (shooter == null) return;
        Point2D.Double p = new Point2D.Double(bullet.getX(), bullet.getY());
        double heading = bullet.getHeadingRadians();
        double ours = Math.abs(Utils.normalRelativeAngle(bearing(p, me()) - heading));
        double other = PI;
        for (Enemy e : enemies.values())
            if (e != shooter && !e.dead && now - e.time <= 12)
                other = Math.min(other, Math.abs(Utils.normalRelativeAngle(bearing(p, e.p) - heading)));
        if (ours < .16 && ours <= other + .03) {
            shooter.rayFocus = Math.min(1, shooter.rayFocus + .45); shooter.lastConfirmedAim = now;
        } else if (other + .12 < ours) shooter.rayFocus *= .5;
    }

    private static int shotBucket(double distance, double power) {
        return (distance < 200 ? 0 : distance < 400 ? 1 : 2) * 4 +
            (power < .7 ? 0 : power < 1.7 ? 1 : power < 2.6 ? 2 : 3);
    }
    private void resolveShot(Shot shot, boolean hit) {
        if (shot == null) return;
        int bucket = shot.bucket; Model model = shot.model;
        model.realAttempts[bucket] = model.realAttempts[bucket] * .98f + 1;
        model.realHits[bucket] = model.realHits[bucket] * .98f + (hit ? 1 : 0);
        if (shot.recovery) {
            model.recoveryMisses = hit ? 0 : model.recoveryMisses + 1;
            if (model.recoveryMisses >= 3) { model.retreatUntil = now + 70; destination = null; }
        }
        dirty = true;
    }

    private void updateTactics() {
        health = getEnergy();
        if (!healing && health < 18) { healing = true; healingEntries++; }
        if (healing && health >= 32) healing = false;
        focusedAttackers = 0; imminentDamage = 0; recoveryTarget = null;
        Point2D.Double self = me();
        for (Enemy enemy : enemies.values()) {
            if (enemy.dead || now - enemy.time > 28) { enemy.focus = 0; continue; }
            double mine = self.distance(enemyPoint(enemy, 0)), nearestOther = Double.POSITIVE_INFINITY;
            for (Enemy other : enemies.values()) {
                if (other == enemy || other.dead || now - other.time > 22) continue;
                nearestOther = Math.min(nearestOther, enemy.p.distance(other.p));
            }
            double proximity = nearestOther == Double.POSITIVE_INFINITY ? .8 :
                clamp(0, 1.4 - mine / Math.max(40, nearestOther), 1);
            double confirmation = enemy.lastConfirmedAim < 0 ? 0 : Math.exp(-(now - enemy.lastConfirmedAim) / 70.0);
            enemy.focus = clamp(.08, .18 + .35 * proximity + .60 * confirmation + .30 * enemy.rayFocus, 1);
            enemy.rayFocus *= .992;
            if (enemy.energy < .1) enemy.focus *= .35;
            enemy.focus *= Math.max(.3, 1 - (now - enemy.time) / 40.0);
            if (enemy.focus >= .48) focusedAttackers++;
        }
        for (Wave wave : incoming) {
            double impact = (self.distance(wave.origin) - radius(wave, now)) / wave.speed;
            if (impact >= -1 && impact <= 18)
                imminentDamage += Rules.getBulletDamage(wave.power) * wave.confidence *
                    (1 - .45 * shieldConfidence(wave.origin, wave.name, self, radius(wave, now), wave.speed));
        }
        if (healing) {
            double best = Double.NEGATIVE_INFINITY;
            for (Enemy enemy : enemies.values()) {
                if (enemy.dead || now - enemy.time > 12 || enemy.focus > .64 || enemy.energy <= 0 || now < enemy.model.retreatUntil) continue;
                double distance = self.distance(enemy.p);
                if (distance > 550) continue;
                Point2D.Double approach = project(enemy.p, bearing(enemy.p, self), 205);
                approach.x = clamp(40, approach.x, width - 40); approach.y = clamp(40, approach.y, height - 40);
                double score = 1.4 * estimatedHitProbability(enemy, Math.min(distance, 220)) -
                    .8 * enemy.focus - distance / 800 - .40 * exposureAt(approach);
                if (score > best) { best = score; recoveryTarget = enemy; }
            }
        }
    }

    /** Previsao compartilhada: evita repetir simulacoes e consultas a API. */
    private Point2D.Double enemyPoint(Enemy enemy, int future) {
        if (enemy.collisionClock != now) {
            long age = Math.min(14, Math.max(0, now - enemy.time));
            Point2D.Double predicted = predict(enemy, age, false);
            for (int tick = 0; tick < enemy.collisionPrediction.length; tick++) {
                enemy.collisionPrediction[tick] = predicted;
                if (age + tick < 14) {
                    predicted = project(predicted, enemy.heading, enemy.velocity);
                    predicted.x = clamp(18, predicted.x, width - 18);
                    predicted.y = clamp(18, predicted.y, height - 18);
                }
            }
            enemy.collisionClock = now;
        }
        return enemy.collisionPrediction[Math.max(0, Math.min(14, future))];
    }

    /**
     * Cobertura parcial e temporaria: um corpo entre a origem do tiro e nos.
     * Para ondas existentes, nao aceita um bloqueador que a bala ja ultrapassou.
     * Usa circulo interno conservador ao corpo de 36x36 e desconta incerteza.
     */
    private double shieldConfidence(Point2D.Double shooter, String shooterName,
                                    Point2D.Double protectedPoint, double traveled, double speed) {
        double dx = protectedPoint.x - shooter.x, dy = protectedPoint.y - shooter.y;
        double length = Math.hypot(dx, dy);
        if (length < 100) return 0;
        double ux = dx / length, uy = dy / length, best = 0;
        for (Enemy blocker : enemies.values()) {
            if (blocker.dead || blocker.name.equals(shooterName) || now - blocker.time > 9) continue;
            Point2D.Double p = enemyPoint(blocker, 0);
            double along = (p.x - shooter.x) * ux + (p.y - shooter.y) * uy;
            if (along <= traveled + 22 || along >= length - 85) continue;
            double flight = Math.max(0, (along - traveled) / Math.max(11, speed));
            p = enemyPoint(blocker, (int)Math.round(Math.min(14, flight)));
            along = (p.x - shooter.x) * ux + (p.y - shooter.y) * uy;
            if (along <= traveled + 22 || along >= length - 85) continue;
            double perpendicular = Math.abs((p.x - shooter.x) * uy - (p.y - shooter.y) * ux);
            double uncertainty = Math.min(8, .32 * Math.abs(blocker.velocity) * flight + .6 * (now - blocker.time));
            double radius = Math.max(6, 16 - uncertainty);
            if (perpendicular < radius) {
                double confidence = .65 * (1 - .55 * perpendicular / radius);
                confidence *= Math.max(.35, 1 - flight / 65);
                best = Math.max(best, confidence);
            }
        }
        return best;
    }

    private double exposureAt(Point2D.Double p) {
        double exposure = 0;
        for (Enemy enemy : enemies.values()) {
            if (enemy.dead || now - enemy.time > 24 || enemy.energy < .1) continue;
            Point2D.Double origin = enemyPoint(enemy, 0);
            double distance = Math.max(90, p.distance(origin));
            double shield = shieldConfidence(origin, enemy.name, p, 0, 14);
            exposure += (.15 + enemy.focus) * 90000 / sq(distance + 100) * (1 - .65 * shield);
        }
        return exposure;
    }

    private double estimatedHitProbability(Enemy enemy, double distance) {
        double learned = .45, evidence = 0;
        for (int gun = 0; gun < GUNS; gun++) {
            double trials = enemy.model.gunTrials[gun];
            if (trials > 12) {
                double rate = (enemy.model.gunHits[gun] + 3) / (trials + 8);
                if (rate > learned || evidence == 0) { learned = rate; evidence = Math.min(1, trials / 100); }
            }
        }
        double geometry = clamp(.08, .95 - distance / 1000 - .025 * Math.abs(enemy.velocity) - .8 * Math.abs(enemy.turn), .95);
        if (Math.abs(enemy.velocity) < .1) geometry = Math.min(.97, geometry + .10);
        double weight = .20 + .35 * evidence;
        double probability = geometry * (1 - weight) + learned * weight;
        int bucket = shotBucket(distance, 2);
        double attempts = enemy.model.realAttempts[bucket];
        if (attempts >= 5) probability = .55 * probability + .45 *
            (enemy.model.realHits[bucket] + 1) / (attempts + 3);
        return clamp(.05, probability * Math.max(.5, 1 - .035 * (now - enemy.time)), .96);
    }

    private double energyReserve() {
        if (health <= 2) return Math.max(.18, health * .65);
        return Math.max(1.1, health * .35 + Math.min(2.5, imminentDamage * .20));
    }

    private void addTacticalDestinations(List<Point2D.Double> points, Point2D.Double self) {
        if (focusedAttackers >= 2 || healing) {
            Enemy shooter = null;
            for (Enemy enemy : enemies.values())
                if (!enemy.dead && now - enemy.time <= 12 && (shooter == null || enemy.focus > shooter.focus)) shooter = enemy;
            if (shooter != null) for (Enemy blocker : enemies.values()) {
                if (blocker == shooter || blocker.dead || now - blocker.time > 8) continue;
                Point2D.Double b = enemyPoint(blocker, 8), origin = enemyPoint(shooter, 0);
                double angle = bearing(origin, b);
                for (double behind : new double[]{115, 175}) {
                    Point2D.Double point = project(b, angle, behind);
                    if (inside(point, 38) && self.distance(point) <= 340 &&
                            shieldConfidence(origin, shooter.name, point, 0, 14) > .15 && escapeOptions(point) >= 2) points.add(point);
                }
            }
        }
        if (healing && recoveryTarget != null) {
            double towardUs = bearing(recoveryTarget.p, self);
            for (double offset : new double[]{-.35, 0, .35}) {
                Point2D.Double point = project(recoveryTarget.p, towardUs + offset, 205);
                if (inside(point, 38) && self.distance(point) <= 340) points.add(point);
            }
        }
    }

    private void selectTarget() {
        Enemy best = null; double bestScore = Double.POSITIVE_INFINITY;
        Point2D.Double self = me();
        for (Enemy e : enemies.values()) {
            if (e.dead || now - e.time > 40) continue;
            double d = self.distance(e.p);
            double score = d * (1 + .012 * Math.min(40, e.energy));
            score *= 1 + .035 * (now - e.time);
            if (e == target) score *= .78; // Evita trocar de alvo a cada tick.
            if (e.energy < 5) score *= .72;
            if (healing) {
                score *= 1 + .5 * e.focus;
                if (e == recoveryTarget) score *= .48;
            }
            if (score < bestScore) { bestScore = score; best = e; }
        }
        if (target != best) lastVirtual = -100;
        target = best;
    }

    private void controlRadar() {
        if (getOthers() > 1 || target == null || now - target.time > 3) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        } else {
            double error = Utils.normalRelativeAngle(bearing(me(), target.p) - getRadarHeadingRadians());
            double extra = Math.min(.35, Math.atan(36 / Math.max(40, me().distance(target.p))));
            setTurnRadarRightRadians(error + sign(error) * extra);
        }
        if (now - lastScan > 15) setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    private void move() {
        updateMobility();
        if (now < escapeUntil) { escapeMove(); return; }
        normalMove();
        // Previsao curta detecta cruzar o corpo de outro robo antes do contato.
        if (wantsMotion) {
            State state = new State(me(), getHeadingRadians(), getVelocity());
            for (int tick = 1; tick <= 6; tick++) {
                State next = step(state, commandAngle, commandSpeed, 0);
                if (collisionRisk(state.p, next.p, tick) >= 8) {
                    beginEscape(); escapeMove(); break;
                }
                state = next;
            }
        }
    }

    private void updateMobility() {
        if (lastMobility == now) return;
        lastMobility = now;
        Point2D.Double self = me();
        boolean blocked = wantsMotion && lastPosition != null && self.distance(lastPosition) < .35;
        blockedTicks = blocked ? blockedTicks + 1 : 0;
        lastPosition = self;
        if (now - lastContact > 12) contactStreak = 0;
        if (blockedTicks >= 8 || contactStreak >= 3) {
            beginEscape(); blockedTicks = 0; contactStreak = 0;
        }
    }

    private void beginEscape() {
        if (now >= escapeUntil) { recoveries++; dirty = true; }
        escapeUntil = now + 18;
        escapeGoal = null; destination = null;
    }

    private void escapeMove() {
        Point2D.Double self = me();
        if (escapeGoal == null || now - lastEscapePlan >= 7 || self.distance(escapeGoal) < 20) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 32; i++) {
                double a = TWO_PI * i / 32;
                Point2D.Double goal = project(self, a, 145);
                goal.x = clamp(28, goal.x, width - 28); goal.y = clamp(28, goal.y, height - 28);
                State state = new State(self, getHeadingRadians(), getVelocity());
                double risk = positionRisk(goal, self);
                for (int t = 1; t <= 18; t++) {
                    State next = step(state, bearing(state.p, goal), 8, 0);
                    risk += collisionRisk(state.p, next.p, t) / Math.sqrt(t);
                    state = next;
                }
                if (contactPoint != null && now - lastContact < 25) {
                    risk += 1000 / sq(Math.max(12, state.p.distance(contactPoint)));
                    risk -= .025 * Math.min(160, state.p.distance(contactPoint));
                }
                if (wallDistance(state.p) < 30) risk += 5;
                if (risk < best) { best = risk; escapeGoal = goal; }
            }
            lastEscapePlan = now;
        }
        goTo(escapeGoal);
    }

    /** Corpos de 36x36: testa tambem cruzar o retangulo entre dois ticks. */
    private double collisionRisk(Point2D.Double from, Point2D.Double to, int future) {
        double risk = 0;
        for (Enemy e : enemies.values()) {
            if (e.dead || now - e.time > 22) continue;
            Point2D.Double p = enemyPoint(e, future);
            double dx = Math.abs(to.x - p.x), dy = Math.abs(to.y - p.y);
            if (dx > 80 || dy > 80) continue;
            boolean leaving = to.distance(p) > from.distance(p) + .15;
            if (dx < 37 && dy < 37 && !leaving) risk += 20;
            Rectangle2D.Double box = new Rectangle2D.Double(p.x - 37, p.y - 37, 74, 74);
            if (!box.contains(from) && box.intersectsLine(from.x, from.y, to.x, to.y)) risk += 20;
            if (to.distance(p) < 75) risk += (75 - to.distance(p)) / 40;
        }
        return risk;
    }

    private void normalMove() {
        if (getOthers() == 1 && target != null && now - target.time < 8) {
            surf(); return;
        }
        // Replaneja regularmente e quando a posicao escolhida fica perigosa.
        if (destination == null || now - lastPlan >= 5 || me().distance(destination) < 35) {
            planMelee(); lastPlan = now;
        }
        goTo(destination);
    }

    private void planMelee() {
        Point2D.Double self = me(), best = null;
        double bestRisk = Double.POSITIVE_INFINITY;
        int candidates = skipped > 3 ? 20 : 36;
        List<Point2D.Double> points = new ArrayList<Point2D.Double>();
        for (int i = 0; i < candidates; i++) {
            Point2D.Double p = project(self, TWO_PI * i / candidates, i % 2 == 0 ? 150 : 235);
            p.x = clamp(36, p.x, width - 36); p.y = clamp(36, p.y, height - 36);
            points.add(p);
        }
        addTacticalDestinations(points, self);
        for (Point2D.Double p : points) {
            double risk = positionRisk(p, self);
            // Testa a rota, nao apenas o ponto final.
            State s = new State(self, getHeadingRadians(), getVelocity());
            for (int t = 1; t <= 24; t++) {
                State next = step(s, bearing(s.p, p), 8, 0);
                risk += collisionRisk(s.p, next.p, t) / Math.sqrt(t);
                s = next;
                if (t % 4 == 0) {
                    risk += .10 * positionRisk(s.p, self);
                    for (Wave w : incoming) {
                        double separation = Math.abs(w.origin.distance(s.p) - radius(w, now + t));
                        if (separation < 32) risk += waveDanger(w, s.p) * w.confidence * 3;
                    }
                }
            }
            if (risk < bestRisk) { bestRisk = risk; best = p; }
        }
        if (best != null && exposureAt(best) < exposureAt(self) * .80) {
            for (Enemy shooter : enemies.values()) {
                if (!shooter.dead && shooter.focus >= .4 && now - shooter.time <= 12 &&
                        shieldConfidence(enemyPoint(shooter, 0), shooter.name, best, 0, 14) > .15) {
                    coveredPlans++; break;
                }
            }
        }
        previousDestination = destination; destination = best;
    }

    private double positionRisk(Point2D.Double p, Point2D.Double start) {
        double risk = 1400 / sq(Math.max(25, wallDistance(p)));
        double centerDistance = p.distance(width / 2, height / 2);
        risk += .10 * (1 - Math.min(1, centerDistance / Math.hypot(width / 2, height / 2)));
        for (Enemy e : enemies.values()) {
            if (e.dead || now - e.time > 45) continue;
            Point2D.Double ep = predict(e, Math.min(8, now - e.time), false);
            double d = Math.max(36, p.distance(ep));
            double confidence = Math.max(.3, 1 - (now - e.time) / 60.0);
            risk += confidence * (16000 + 100 * Math.min(80, e.energy)) / sq(d);
            if (d < 125) risk += (125 - d) / 20;
            // Deslocamento lateral ao inimigo reduz tiros head-on/lineares.
            double travel = bearing(start, p), enemyAngle = bearing(ep, start);
            risk += .10 * sq(Math.cos(travel - enemyAngle)) * confidence;
        }
        if (previousDestination != null)
            risk += 450 / sq(Math.max(45, p.distance(previousDestination)));
        risk += (focusedAttackers >= 2 ? .65 : .22) * exposureAt(p);
        if ((focusedAttackers >= 2 || healing) && escapeOptions(p) < 2) risk += .9;
        if (healing && recoveryTarget != null) {
            double distance = p.distance(enemyPoint(recoveryTarget, 6));
            double opportunity = Math.exp(-sq((distance - 210) / 150));
            risk -= .95 * opportunity * (1 - recoveryTarget.focus);
            risk += .30 * exposureAt(p); // Sobreviver continua tendo prioridade.
        }
        return risk;
    }

    private void surf() {
        Point2D.Double self = me();
        Wave nearest = null; double soonest = Double.POSITIVE_INFINITY;
        for (Wave w : incoming) {
            double remaining = (self.distance(w.origin) - radius(w, now)) / w.speed;
            if (remaining > -2 && remaining < soonest) { nearest = w; soonest = remaining; }
        }
        if (nearest == null) {
            double distance = self.distance(target.p);
            if (target.energy < .1 && incoming.isEmpty()) {
                goTo(project(target.p, bearing(target.p, self), 85)); return;
            }
            double a = bearing(target.p, self) + orbit * (PI / 2 + clamp(-.4, (distance - desiredRange()) / desiredRange(), .5));
            goAngle(smooth(self, a, orbit), 8); return;
        }
        int bestDirection = orbit, bestSwitch = 999, bestAfter = orbit;
        double best = Double.POSITIVE_INFINITY;
        int[] switchTicks = skipped > 3 ? new int[]{999, 6} : new int[]{999, 4, 9};
        for (int initial = -1; initial <= 1; initial++) {
            for (int sw : switchTicks) {
                int after = initial == 0 ? orbit : -initial;
                if (sw == 999) after = initial;
                double risk = surfPlanRisk(nearest, initial, sw, after);
                if (initial != 0 && initial != orbit) risk += .003;
                if (initial == 0) risk += .008;
                if (risk < best) {
                    best = risk; bestDirection = initial; bestSwitch = sw; bestAfter = after;
                }
            }
        }
        int direction = bestSwitch == 0 ? bestAfter : bestDirection;
        if (direction == 0) { goAngle(getHeadingRadians(), 0); return; }
        orbit = direction;
        double distance = self.distance(nearest.origin);
        double angle = bearing(nearest.origin, self) + direction *
            (PI / 2 + clamp(-.45, (distance - desiredRange()) / 400, .50));
        goAngle(smooth(self, angle, direction), 8);
    }

    private double surfPlanRisk(Wave main, int initial, int switchTick, int after) {
        State state = new State(me(), getHeadingRadians(), getVelocity());
        double risk = 0;
        Set<Wave> crossed = new HashSet<Wave>();
        int limit = skipped > 3 ? 60 : 90;
        int impactTime = -1;
        for (int tick = 1; tick <= limit; tick++) {
            int direction = tick <= switchTick ? initial : after;
            if (switchTick < 999 && tick > switchTick + 14) direction = initial == 0 ? after : initial;
            int orbitDirection = direction == 0 ? orbit : direction;
            double distance = state.p.distance(main.origin);
            double angle = direction == 0 ? state.heading : bearing(main.origin, state.p) + orbitDirection *
                (PI / 2 + clamp(-.45, (distance - desiredRange()) / 400, .50));
            State next = step(state, angle, direction == 0 ? 0 : 8, direction == 0 ? 0 : direction);
            risk += collisionRisk(state.p, next.p, tick) / Math.sqrt(tick + 1);
            state = next;
            for (Wave w : incoming) {
                if (crossed.contains(w)) continue;
                double before = radius(w, now + tick - 1), afterRadius = before + w.speed;
                double d = w.origin.distance(state.p);
                if (d >= before - 25 && d <= afterRadius + 25) {
                    double urgency = w == main ? 1 : .55;
                    risk += waveDanger(w, state.p) * w.confidence * urgency;
                    crossed.add(w);
                    if (w == main) impactTime = tick;
                }
            }
            risk += .0005 * 900 / sq(Math.max(18, wallDistance(state.p)));
            // Inclui a segunda onda, ate 16 ticks depois da primeira.
            if (impactTime >= 0 && tick >= impactTime + 28) break;
        }
        if (target != null) {
            double endDistance = state.p.distance(predict(target, 12, false));
            risk += .015 * Math.abs(endDistance - desiredRange()) / desiredRange();
        }
        return risk;
    }

    private State step(State s, double desired, double maxSpeed, int smoothingDirection) {
        if (smoothingDirection != 0) desired = smooth(s.p, desired, smoothingDirection);
        double relative = Utils.normalRelativeAngle(desired - s.heading);
        int drive = 1;
        if (Math.abs(relative) > PI / 2) {
            relative = Utils.normalRelativeAngle(relative + PI); drive = -1;
        }
        double turnLimit = Math.toRadians(10 - .75 * Math.abs(s.velocity));
        double heading = s.heading + clamp(-turnLimit, relative, turnLimit);
        double v = nextVelocity(s.velocity, drive * maxSpeed);
        Point2D.Double p = project(s.p, heading, v);
        if (!inside(p, 18)) {
            p.x = clamp(18, p.x, width - 18); p.y = clamp(18, p.y, height - 18); v = 0;
        }
        return new State(p, heading, v);
    }

    // Aceleracao +1, frenagem -2 e reversao fracionaria conforme fisica classica.
    private static double nextVelocity(double v, double goal) {
        if (goal < 0) return -nextVelocity(-v, -goal);
        if (v >= 0) return clamp(v - 2, goal, v + 1);
        double deceleration = Math.min(1, -v / 2) * 2 + Math.max(0, 1 + v / 2);
        return Math.min(goal, v + deceleration);
    }

    private void goTo(Point2D.Double p) {
        if (p == null) {
            wantsMotion = true; commandAngle = getHeadingRadians() + .3; commandSpeed = 8;
            setMaxVelocity(8); setAhead(100); setTurnRight(20); return;
        }
        double distance = me().distance(p);
        commandAngle = bearing(me(), p); commandSpeed = 8; wantsMotion = distance > 20;
        double relative = Utils.normalRelativeAngle(bearing(me(), p) - getHeadingRadians());
        int direction = 1;
        if (Math.abs(relative) > PI / 2) { relative = Utils.normalRelativeAngle(relative + PI); direction = -1; }
        setTurnRightRadians(relative); setMaxVelocity(Math.abs(relative) > 1.1 ? 5 : 8);
        setAhead(direction * distance);
    }

    private void goAngle(double angle, double speed) {
        wantsMotion = speed > .1; commandAngle = angle; commandSpeed = speed;
        double relative = Utils.normalRelativeAngle(angle - getHeadingRadians());
        int direction = 1;
        if (Math.abs(relative) > PI / 2) { relative = Utils.normalRelativeAngle(relative + PI); direction = -1; }
        setTurnRightRadians(relative); setMaxVelocity(speed); setAhead(120 * direction);
    }

    private double smooth(Point2D.Double p, double angle, int direction) {
        double stick = Math.min(140, Math.min(width, height) * .25);
        for (int i = 0; i < 100 && !inside(project(p, angle, stick), 25); i++) angle += direction * .05;
        return angle;
    }

    private void aimAndFire() {
        if (target == null || now - target.time > 10) return;
        Point2D.Double self = me();
        double distance = self.distance(target.p);
        double power = choosePower(target, distance);
        double[] angles = gunAngles(target, self, Math.max(.1, power));
        int selected = 2; // Circular antes de existir evidencia suficiente.
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < angles.length; i++) {
            double prior = i == 2 ? .28 : (i == 1 ? .25 : .20);
            double score = (target.model.gunHits[i] + 8 * prior) / (target.model.gunTrials[i] + 8);
            if (score > best) { best = score; selected = i; }
        }
        if (Math.abs(target.velocity) < .01) selected = 0;
        double aim = angles[selected];
        setTurnGunRightRadians(Utils.normalRelativeAngle(aim - getGunHeadingRadians()));
        // Ondas virtuais aprendem mesmo quando o canhao esta resfriando.
        if (now - lastVirtual >= 5 && now == target.time && outgoing.size() < MAX_WAVES) {
            GunWave w = new GunWave();
            w.origin = self; w.time = now; w.speed = 20 - 3 * Math.max(.1, power);
            w.bearing = bearing(self, target.p); w.direction = target.direction;
            w.segment = segment(distance, Math.abs(target.velocity), wallDistance(target.p));
            w.angles = angles.clone(); w.model = target.model; w.name = target.name;
            w.features = features(target, self);
            outgoing.add(w); lastVirtual = now;
        }
        double error = Math.abs(Utils.normalRelativeAngle(aim - getGunHeadingRadians()));
        double tolerance = Math.atan(15 / Math.max(30, distance));
        if (power >= .1 && getGunHeat() == 0 && getEnergy() > power + .12
                && now - target.time <= (getOthers() > 1 ? 5 : 1)
                && error < tolerance && !blocked(self, aim, distance)) {
            Bullet bullet = setFireBullet(power);
            if (bullet != null) {
                realShots.put(bullet, new Shot(target.model, now, shotBucket(distance, power), healing));
                target.model.shots++; shots++; if (healing) recoveryShots++; dirty = true;
            }
        }
    }

    private double choosePower(Enemy e, double distance) {
        double power = distance < 150 ? 3 : distance < 330 ? 2.5 : distance < 550 ? 1.9 : 1.2;
        if (getOthers() > 1 && distance > 350) power = Math.min(power, 1.7);
        double kill = e.energy <= 4 ? e.energy / 4 : (e.energy + 2) / 6;
        if (!healing) return clamp(.1, Math.min(Math.min(power, kill + .03), health / 6), 3);
        double probability = estimatedHitProbability(e, distance);
        // Ganho esperado = potencia*(3*probabilidade-1); abaixo de 1/3 e negativo.
        // Usa margem maior para nao apostar a sobrevivencia em uma estimativa incerta.
        if (probability < .43 || now - e.time > 5 || now < e.model.retreatUntil) return 0;
        double budget = health - energyReserve();
        if (budget < .1) return 0;
        power = probability >= .58 ? 1.6 : .45;
        boolean pressured = focusedAttackers >= 2 || imminentDamage > health * .7;
        if (probability >= .70 && distance <= 240 && e.focus < .65 && !pressured) power = 3;
        else if (probability >= .62 && distance < 320 && !pressured) power = 2.2;
        if (health < 3) power = Math.min(power, .5);
        int bucket = shotBucket(distance, power);
        double attempts = e.model.realAttempts[bucket];
        if (attempts >= 5) {
            double calibrated = .5 * probability + .5 * (e.model.realHits[bucket] + 1) / (attempts + 3);
            if (calibrated < .40) return 0;
            if (calibrated < .58) power = Math.min(power, .45);
        }
        // Nao gasta mais do que precisa para finalizar um alvo, nem a reserva defensiva.
        return Math.max(.1, Math.min(Math.min(power, Math.max(.1, kill + .03)), budget));
    }

    private double[] gunAngles(Enemy e, Point2D.Double self, double power) {
        double speed = 20 - 3 * power;
        double direct = bearing(self, e.p);
        Point2D.Double linear = intercept(e, self, speed, false);
        Point2D.Double circular = intercept(e, self, speed, true);
        Point2D.Double acceleration = acceleratingIntercept(e, self, speed);
        int seg = segment(self.distance(e.p), Math.abs(e.velocity), wallDistance(e.p));
        double best = -1; int index = MID;
        for (int i = 0; i < BINS; i++) {
            double value = histogram(e.model.gun, seg, i);
            // Prior leve de mira frontal para dados ainda escassos.
            value += .15 / (1 + sq((i - MID) / 4.0));
            if (value > best) { best = value; index = i; }
        }
        double gfAngle = direct + e.direction * ((index - MID) / (double)MID) * Math.asin(8 / speed);
        if (e.gfTime != e.time || e.gfSamples != e.model.samples.size()) {
            e.cachedGF = queryGF(e.model, features(e, self), (index - MID) / (double)MID);
            e.gfTime = e.time; e.gfSamples = e.model.samples.size();
        }
        double gf = e.cachedGF;
        double knnAngle = direct + e.direction * gf * Math.asin(8 / speed);
        return new double[] {direct, bearing(self, linear), bearing(self, circular), gfAngle,
            bearing(self, acceleration), knnAngle};
    }

    private Point2D.Double intercept(Enemy e, Point2D.Double self, double speed, boolean circular) {
        Point2D.Double p = predict(e, Math.min(10, now - e.time), circular);
        double h = e.heading + (circular ? e.turn * Math.min(10, now - e.time) : 0);
        double v = e.velocity;
        for (int t = 1; t < 150; t++) {
            h += circular ? e.turn : 0;
            Point2D.Double next = project(p, h, v);
            if (!inside(next, 18)) {
                next.x = clamp(18, next.x, width - 18); next.y = clamp(18, next.y, height - 18);
                p = next; break;
            }
            p = next;
            if (t * speed >= self.distance(p)) break;
        }
        return p;
    }

    private Point2D.Double predict(Enemy e, long ticks, boolean circular) {
        Point2D.Double p = copy(e.p); double h = e.heading;
        for (int i = 0; i < ticks; i++) {
            h += circular ? e.turn : 0;
            p = project(p, h, e.velocity);
            p.x = clamp(18, p.x, width - 18); p.y = clamp(18, p.y, height - 18);
        }
        return p;
    }

    private boolean blocked(Point2D.Double self, double angle, double distance) {
        for (Enemy e : enemies.values()) {
            if (e == target || e.dead || now - e.time > 12) continue;
            double d = self.distance(e.p);
            double a = Math.abs(Utils.normalRelativeAngle(bearing(self, e.p) - angle));
            if (d < distance - 30 && a < Math.atan(23 / Math.max(25, d))) return true;
        }
        return false;
    }

    private void updateGunWaves(Enemy e) {
        Iterator<GunWave> it = outgoing.iterator();
        while (it.hasNext()) {
            GunWave w = it.next();
            if (!w.name.equals(e.name)) continue;
            if (radius(w, now) < w.origin.distance(e.p) - 8) continue;
            long gap = e.previousTime >= 0 ? now - e.previousTime : 1;
            if (gap > 14) { it.remove(); continue; }
            Point2D.Double point = e.p;
            // Interpola scans de melee: nao usa a posicao atrasada como acerto exato.
            double bestError = Double.POSITIVE_INFINITY;
            for (int tick = 0; tick <= gap; tick++) {
                long time = now - gap + tick;
                if (time < w.time) continue;
                double fraction = gap == 0 ? 1 : tick / (double)gap;
                Point2D.Double p = new Point2D.Double(
                    e.previousPoint.x + fraction * (e.p.x - e.previousPoint.x),
                    e.previousPoint.y + fraction * (e.p.y - e.previousPoint.y));
                double error = Math.abs(radius(w, time) - w.origin.distance(p));
                if (error < bestError) { bestError = error; point = p; }
            }
            if (bestError > w.speed * 2) { it.remove(); continue; }
            double weight = 1 / (1 + .25 * Math.max(0, gap - 1));
            int bin = factorIndex(w, point);
            learn(w.model.gun[w.segment], bin, weight);
            double actual = bearing(w.origin, point), distance = w.origin.distance(point);
            double tolerance = Math.atan(18 / Math.max(30, distance));
            for (int i = 0; i < GUNS; i++) {
                double error = Math.abs(Utils.normalRelativeAngle(actual - w.angles[i]));
                w.model.gunTrials[i] = w.model.gunTrials[i] * .995 + weight;
                w.model.gunHits[i] = w.model.gunHits[i] * .995 +
                    weight * Math.exp(-.5 * sq(error / Math.max(.015, tolerance)));
            }
            double factor = Utils.normalRelativeAngle(actual - w.bearing) /
                Math.asin(8 / w.speed) * w.direction;
            float[] sample = new float[FEATURES + 2];
            for (int f = 0; f < FEATURES; f++) sample[f] = (float)w.features[f];
            sample[FEATURES] = (float)clamp(-1, factor, 1); sample[FEATURES + 1] = (float)weight;
            if (w.model.samples.size() == MAX_SAMPLES) w.model.samples.remove(0);
            w.model.samples.add(sample); dirty = true;
            it.remove();
        }
    }

    private Point2D.Double acceleratingIntercept(Enemy e, Point2D.Double self, double speed) {
        Point2D.Double p = predict(e, Math.min(8, now - e.time), true);
        double heading = e.heading, velocity = e.velocity;
        for (int tick = 1; tick < 130; tick++) {
            heading += e.turn;
            if (tick <= 8 && Math.abs(velocity) > .1)
                velocity = sign(velocity) * clamp(0, Math.abs(velocity) + e.acceleration, 8);
            Point2D.Double next = project(p, heading, velocity);
            if (!inside(next, 18)) break;
            p = next;
            if (tick * speed >= self.distance(p)) break;
        }
        return p;
    }

    private double[] features(Enemy e, Point2D.Double self) {
        double abs = bearing(self, e.p), lateral = e.velocity * Math.sin(e.heading - abs);
        double advancing = -e.velocity * Math.cos(e.heading - abs);
        double room = roomAhead(e.p, e.heading + (e.velocity < 0 ? PI : 0));
        double myLateral = getVelocity() * Math.sin(getHeadingRadians() - (abs + PI));
        return new double[] {
            clamp(0, self.distance(e.p) / 1000, 1), Math.abs(lateral) / 8,
            (advancing + 8) / 16, (e.acceleration + 2) / 3,
            clamp(0, room / 500, 1), clamp(0, (now - e.directionChanged) / 50.0, 1),
            (clamp(-8, myLateral * e.direction, 8) + 8) / 16
        };
    }

    private double roomAhead(Point2D.Double p, double angle) {
        double sx = Math.sin(angle), cy = Math.cos(angle);
        double x = Math.abs(sx) < 1e-8 ? 1e9 : ((sx > 0 ? width - 18 : 18) - p.x) / sx;
        double y = Math.abs(cy) < 1e-8 ? 1e9 : ((cy > 0 ? height - 18 : 18) - p.y) / cy;
        return Math.max(0, Math.min(x, y));
    }

    private static double queryGF(Model model, double[] query, double fallback) {
        int count = model.samples.size();
        if (count < 12) return fallback;
        int k = Math.min(NEIGHBORS, count);
        double[] distances = new double[k]; int[] indices = new int[k];
        Arrays.fill(distances, Double.POSITIVE_INFINITY); Arrays.fill(indices, -1);
        for (int n = 0; n < count; n++) {
            float[] sample = model.samples.get(n); double distance = 0;
            for (int f = 0; f < FEATURES; f++) distance += FEATURE_WEIGHTS[f] * sq(query[f] - sample[f]);
            if (distance >= distances[k - 1]) continue;
            int j = k - 1;
            while (j > 0 && distances[j - 1] > distance) {
                distances[j] = distances[j - 1]; indices[j] = indices[j - 1]; j--;
            }
            distances[j] = distance; indices[j] = n;
        }
        double bandwidth = .03 + distances[k - 1] / 2, best = -1, result = fallback;
        for (int bin = 0; bin < BINS; bin++) {
            double gf = (bin - MID) / (double)MID, density = 0;
            for (int n = 0; n < k; n++) {
                float[] sample = model.samples.get(indices[n]);
                double recency = .65 + .35 * indices[n] / Math.max(1.0, count - 1);
                density += sample[FEATURES + 1] * recency / (1 + distances[n] / bandwidth) *
                    Math.exp(-.5 * sq((gf - sample[FEATURES]) / .12));
            }
            if (density > best) { best = density; result = gf; }
        }
        return result;
    }

    private void updateWaves() {
        Point2D.Double self = me();
        Iterator<Wave> it = incoming.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            if (!w.visited && radius(w, now) >= w.origin.distance(self)) registerVisit(w, self);
            if (radius(w, now) > w.origin.distance(self) + 65 || now - w.time > 180) it.remove();
        }
        Iterator<Map.Entry<Bullet, Shot>> bullets = realShots.entrySet().iterator();
        while (bullets.hasNext()) if (now - bullets.next().getValue().time > 200) bullets.remove();
        Iterator<GunWave> guns = outgoing.iterator();
        while (guns.hasNext()) {
            GunWave w = guns.next(); Enemy e = enemies.get(w.name);
            if (now - w.time > 160 || e == null || e.dead) guns.remove();
        }
    }

    private double waveDanger(Wave w, Point2D.Double p) {
        int index = factorIndex(w, p);
        double learned = histogram(w.model.surf, w.segment, index);
        double sum = .01;
        for (int i = 0; i < BINS; i++) sum += histogram(w.model.surf, w.segment, i);
        // Priors frontal e linear; normalizacao permite comparar inimigos.
        double gf = (index - MID) / (double)MID;
        double prior = .7 / (1 + sq(gf * 5)) + .3 / (1 + sq((Math.abs(gf) - .8) * 5));
        double visits = .01;
        for (float value : w.model.visits) visits += value;
        double flatten = w.model.waveBreaks > 8
            ? .40 * clamp(0, (w.model.surfHits / w.model.waveBreaks - .06) / .15, 1) : 0;
        double profile = (1 - flatten) * learned / sum + flatten * w.model.visits[index] / visits;
        double shield = shieldConfidence(w.origin, w.name, p, radius(w, now), w.speed);
        return (1 + w.power / 6) * (.12 + prior * .28 + profile * BINS) * (1 - .55 * shield);
    }

    private void registerVisit(Wave w, Point2D.Double p) {
        if (w.visited) return;
        w.visited = true;
        learn(w.model.visits, factorIndex(w, p), w.confidence);
        w.model.waveBreaks = w.model.waveBreaks * .99 + w.confidence;
        w.model.surfHits *= .99;
        dirty = true;
    }

    private static double histogram(float[][] data, int segment, int index) {
        double value = data[segment][index];
        for (int i = 0; i < SEGMENTS; i++) value += .08 * data[i][index];
        return value;
    }
    private static void learn(float[] histogram, int bin, double weight) {
        for (int i = 0; i < BINS; i++)
            histogram[i] = (float)(histogram[i] * .999 + weight / (1 + sq(i - bin)));
    }
    private static int factorIndex(Wave w, Point2D.Double p) {
        double offset = Utils.normalRelativeAngle(bearing(w.origin, p) - w.bearing);
        double factor = offset / Math.asin(8 / w.speed) * w.direction;
        return (int)Math.round(clamp(0, MID * (factor + 1), BINS - 1));
    }
    private static double radius(Wave w, long time) { return Math.max(0, time - w.time) * w.speed; }

    @Override public void onHitByBullet(HitByBulletEvent e) {
        now = getTime();
        receivedHits++;
        Enemy shooter = enemies.get(e.getName());
        if (shooter != null) {
            shooter.bonus += 3 * e.getPower(); shooter.lastConfirmedAim = now; shooter.rayFocus = 1;
        }
        matchWave(e.getName(), e.getBullet(), me(), true);
        destination = null;
    }
    @Override public void onBulletHitBullet(BulletHitBulletEvent e) {
        now = getTime();
        resolveShot(realShots.remove(e.getBullet()), false);
        Bullet bullet = e.getHitBullet();
        observeRay(bullet);
        Enemy shooter = enemies.get(bullet.getName());
        double towardUs = bearing(new Point2D.Double(bullet.getX(), bullet.getY()), me());
        if (shooter != null && Math.abs(Utils.normalRelativeAngle(bullet.getHeadingRadians() - towardUs)) < .16)
            shooter.lastConfirmedAim = now;
        matchWave(bullet.getName(), bullet, new Point2D.Double(bullet.getX(), bullet.getY()), false);
    }
    private void matchWave(String name, Bullet bullet, Point2D.Double hit, boolean damage) {
        Wave best = null; double error = Double.POSITIVE_INFINITY;
        for (Wave w : incoming) {
            if (!w.name.equals(name) || Math.abs(w.speed - bullet.getVelocity()) > .15) continue;
            double difference = Math.abs(w.origin.distance(hit) - radius(w, now));
            if (difference < error) { error = difference; best = w; }
        }
        if (best != null && error < (getOthers() == 1 ? 50 : 110)) {
            registerVisit(best, hit);
            if (damage) best.model.surfHits += best.confidence;
            dirty = true;
            learn(best.model.surf[best.segment], factorIndex(best, hit), damage ? 1 : .35);
            incoming.remove(best);
        }
    }
    @Override public void onBulletHit(BulletHitEvent e) {
        now = getTime();
        Shot shot = realShots.remove(e.getBullet());
        if (shot != null) { shot.model.hits++; hits++; resolveShot(shot, true); dirty = true; }
        Enemy victim = enemies.get(e.getName());
        if (victim != null) victim.damage += Rules.getBulletDamage(e.getBullet().getPower());
    }
    @Override public void onRobotDeath(RobotDeathEvent e) {
        now = getTime();
        Enemy en = enemies.get(e.getName()); if (en != null) en.dead = true;
        if (target == en) target = null;
        destination = null;
        // Tiros ja disparados pelo inimigo continuam ativos apos a morte dele.
    }
    @Override public void onHitWall(HitWallEvent e) {
        now = getTime(); wallContacts++;
        orbit = -orbit; destination = null; beginEscape();
    }
    @Override public void onHitRobot(HitRobotEvent e) {
        now = getTime(); robotContacts++;
        Enemy en = enemies.get(e.getName());
        if (en != null) en.damage += .6;
        contactPoint = project(me(), getHeadingRadians() + e.getBearingRadians(), 36);
        if (now - lastContact <= 4) contactStreak++; else contactStreak = 1;
        lastContact = now;
        if (now >= escapeUntil || now - lastEscapePlan > 5) beginEscape();
        destination = null;
    }
    @Override public void onBulletMissed(BulletMissedEvent e) { now = getTime(); resolveShot(realShots.remove(e.getBullet()), false); }
    @Override public void onSkippedTurn(SkippedTurnEvent e) { skipped++; }
    @Override public void onWin(WinEvent e) { finishRound(); }
    @Override public void onDeath(DeathEvent e) { finishRound(); }
    @Override public void onRoundEnded(RoundEndedEvent e) { finishRound(); }
    private void finishRound() {
        now = getTime();
        if (roundEnded) return;
        roundEnded = true; roundsTotal++; dirty = true; saveLearning(true);
        int samples = 0;
        for (Model model : MEMORY.values()) samples += model.samples.size();
        out.println("[Titan R" + (getRoundNum() + 1) + "] acertos/tiros=" + hits + "/" + shots +
            " recebidos=" + receivedHits + " escapes=" + recoveries + " skips=" + skipped +
            " amostras=" + samples + " checkpoints=" + checkpoints +
            " recuperacoes=" + healingEntries + " tirosRecup=" + recoveryShots + " rotasProtegidas=" + coveredPlans + " contatos=" + robotContacts +
            " paredes=" + wallContacts + " energiaFinal=" + getEnergy());
    }

    private Model model(String name) {
        // Versao faz parte da chave: nao mistura silenciosamente robos diferentes.
        Model m = MEMORY.get(name);
        if (m == null) {
            if (MEMORY.size() >= MAX_MODELS) {
                // Nao descarta modelos usados por adversarios da batalha atual.
                Iterator<String> keys = MEMORY.keySet().iterator();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!enemies.containsKey(key)) { keys.remove(); break; }
                }
                if (MEMORY.size() >= MAX_MODELS) MEMORY.remove(MEMORY.keySet().iterator().next());
            }
            m = new Model(); MEMORY.put(name, m);
        }
        return m;
    }

    /** Carrega a copia valida mais recente; a anterior sobrevive a escrita interrompida. */
    private void loadLearning() {
        if (loaded) return;
        loaded = true;
        Snapshot best = null;
        for (String name : new String[]{SNAP_A, SNAP_B}) {
            File file = getDataFile(name);
            if (file == null || !file.isFile() || file.length() == 0) continue;
            try {
                Snapshot snapshot = readSnapshot(file);
                if (best == null || snapshot.generation > best.generation) best = snapshot;
            } catch (IOException ex) { out.println("[Titan] Copia ignorada " + name + ": " + ex.getMessage()); }
        }
        if (best != null) {
            MEMORY.clear(); MEMORY.putAll(best.models);
            generation = best.generation; roundsTotal = best.rounds;
            out.println("[Titan] Aprendizado carregado: geracao=" + generation +
                " adversarios=" + MEMORY.size() + " rounds=" + roundsTotal);
        } else {
            loadLegacy();
        }
    }

    /** Migra a primeira versao monolitica; nao mistura formatos dos modulos do ZIP. */
    private void loadLegacy() {
        File file = getDataFile(DATA);
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > 100000) return;
        LinkedHashMap<String, Model> restored = new LinkedHashMap<String, Model>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Formato legado invalido");
            int count = in.readInt();
            if (count < 0 || count > 16) throw new IOException("Contagem invalida");
            for (int n = 0; n < count; n++) {
                String name = in.readUTF(); Model m = new Model();
                double trials = checked(in.readDouble());
                for (int i = 0; i < 4; i++) { m.gunTrials[i] = trials; m.gunHits[i] = checked(in.readDouble()); }
                for (int s = 0; s < SEGMENTS; s++) for (int b = 0; b < BINS; b++) {
                    m.gun[s][b] = (float)checked(in.readFloat());
                    m.surf[s][b] = (float)checked(in.readFloat());
                }
                restored.put(name, m);
                if (restored.size() > MAX_MODELS) restored.remove(restored.keySet().iterator().next());
            }
            if (in.read() != -1) throw new IOException("Dados extras");
            MEMORY.putAll(restored); dirty = true;
            out.println("[Titan] Memoria v1 importada: " + restored.size() + " adversarios.");
        } catch (IOException ex) { out.println("[Titan] Memoria v1 ignorada: " + ex.getMessage()); }
    }

    private void saveLearning(boolean force) {
        if (!dirty && !force) return;
        lastCheckpoint = now;
        if (!force) {
            try {
                checkpoint = new CheckpointJob(new ArrayList<Map.Entry<String, Model>>(MEMORY.entrySet()),
                    96, generation + 1, roundsTotal);
            } catch (IOException ex) { out.println("[Titan] Checkpoint adiado: " + ex.getMessage()); }
            return;
        }
        cancelCheckpoint();
        String slot = (generation + 1) % 2 == 1 ? SNAP_A : SNAP_B;
        try {
            ArrayList<Map.Entry<String, Model>> entries = new ArrayList<Map.Entry<String, Model>>(MEMORY.entrySet());
            int sampleLimit = 96;
            byte[] bytes = encodeSnapshot(entries, sampleLimit, generation + 1, roundsTotal);
            while (bytes.length > MAX_SNAPSHOT_BYTES && sampleLimit > 0) {
                sampleLimit = Math.max(0, sampleLimit - 24);
                bytes = encodeSnapshot(entries, sampleLimit, generation + 1, roundsTotal);
            }
            // Se necessario, compacta apenas o snapshot; preserva todos os modelos na RAM.
            while (bytes.length > MAX_SNAPSHOT_BYTES && entries.size() > 1) {
                entries.remove(0); bytes = encodeSnapshot(entries, sampleLimit, generation + 1, roundsTotal);
            }
            File file = getDataFile(slot);
            if (file == null || bytes.length > MAX_SNAPSHOT_BYTES ||
                    bytes.length > getDataQuotaAvailable() + file.length()) throw new IOException("Cota insuficiente");
            try (OutputStream output = new RobocodeFileOutputStream(file)) { output.write(bytes); }
            Snapshot verified = readSnapshot(file); // valida CRC, limites e terminacao completa
            if (verified.generation != generation + 1) throw new IOException("Geracao nao confirmada");
            generation++; checkpoints++; dirty = false;
            out.println("[Titan] Checkpoint g=" + generation + " tick=" + now +
                " bytes=" + bytes.length + " modelos=" + entries.size());
        } catch (IOException ex) { out.println("[Titan] Falha no checkpoint: " + ex.getMessage()); }
    }

    private static byte[] encodeSnapshot(List<Map.Entry<String, Model>> entries, int sampleLimit,
                                         long nextGeneration, long rounds) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new FastGzip(buffer))) {
            writeHeader(output, nextGeneration, rounds, entries.size());
            for (Map.Entry<String, Model> entry : entries)
                writeModel(output, entry.getKey(), entry.getValue(), sampleLimit);
        }
        return buffer.toByteArray();
    }

    private CheckpointJob checkpoint;

    /** Divide serializacao, escrita e verificacao entre ticks, sem criar threads. */
    private void checkpointTick() {
        if (checkpoint == null) return;
        CheckpointJob job = checkpoint;
        try {
            if (job.index < job.entries.size()) {
                Map.Entry<String, Model> entry = job.entries.get(job.index++);
                writeModel(job.output, entry.getKey(), entry.getValue(), job.sampleLimit);
                return;
            }
            if (job.bytes == null) {
                job.output.close(); job.bytes = job.buffer.toByteArray();
                if (job.bytes.length > MAX_SNAPSHOT_BYTES) {
                    int nextLimit = Math.max(0, job.sampleLimit - 24);
                    if (job.sampleLimit == 0 && job.entries.size() > 1) job.entries.remove(0);
                    else if (job.sampleLimit == 0) throw new IOException("Snapshot grande demais");
                    checkpoint = new CheckpointJob(job.entries, nextLimit, job.generation, job.rounds);
                    return;
                }
                File file = getDataFile(job.slot);
                if (file == null || job.bytes.length > getDataQuotaAvailable() + file.length())
                    throw new IOException("Cota insuficiente");
                job.file = file; job.writer = new RobocodeFileOutputStream(file);
                return;
            }
            if (job.writer != null) {
                int count = Math.min(2048, job.bytes.length - job.offset);
                job.writer.write(job.bytes, job.offset, count); job.offset += count;
                if (job.offset == job.bytes.length) { job.writer.close(); job.writer = null; }
                return;
            }
            if (job.verifier == null) {
                job.verifier = new GZIPInputStream(new FileInputStream(job.file)); return;
            }
            if (job.verifier.read(job.verificationBuffer) >= 0) return;
            job.verifier.close(); job.verifier = null;
            generation = job.generation; checkpoints++; checkpoint = null;
            out.println("[Titan] Checkpoint g=" + generation + " tick=" + now +
                " bytes=" + job.bytes.length + " modelos=" + job.entries.size());
        } catch (IOException ex) {
            cancelCheckpoint();
            out.println("[Titan] Checkpoint adiado: " + ex.getMessage());
        }
    }

    private void cancelCheckpoint() {
        if (checkpoint == null) return;
        try { checkpoint.output.close(); } catch (IOException ignored) { }
        if (checkpoint.writer != null) try { checkpoint.writer.close(); } catch (IOException ignored) { }
        if (checkpoint.verifier != null) try { checkpoint.verifier.close(); } catch (IOException ignored) { }
        checkpoint = null;
    }

    private static class CheckpointJob {
        final ArrayList<Map.Entry<String, Model>> entries;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final DataOutputStream output;
        final int sampleLimit;
        final long generation, rounds;
        final String slot;
        final byte[] verificationBuffer = new byte[2048];
        int index, offset; byte[] bytes; File file;
        OutputStream writer; InputStream verifier;
        CheckpointJob(List<Map.Entry<String, Model>> entries, int sampleLimit,
                      long generation, long rounds) throws IOException {
            this.entries = new ArrayList<Map.Entry<String, Model>>(entries);
            this.sampleLimit = sampleLimit; this.generation = generation; this.rounds = rounds;
            slot = generation % 2 == 1 ? SNAP_A : SNAP_B;
            output = new DataOutputStream(new FastGzip(buffer));
            writeHeader(output, generation, rounds, entries.size());
        }
    }

    private static void writeHeader(DataOutputStream output, long generation, long rounds, int count) throws IOException {
        output.writeInt(MAGIC_V2); output.writeInt(3); output.writeLong(generation);
        output.writeLong(rounds); output.writeInt(count);
    }

    private static void writeModel(DataOutputStream output, String name, Model m, int sampleLimit) throws IOException {
        output.writeUTF(name);
        for (int gun = 0; gun < GUNS; gun++) {
            output.writeDouble(m.gunHits[gun]); output.writeDouble(m.gunTrials[gun]);
        }
        output.writeDouble(m.enemyWaves); output.writeDouble(m.waveBreaks); output.writeDouble(m.surfHits);
        output.writeLong(m.shots); output.writeLong(m.hits);
        for (int i = 0; i < 12; i++) { output.writeFloat(m.realAttempts[i]); output.writeFloat(m.realHits[i]); }
        for (int s = 0; s < SEGMENTS; s++) for (int b = 0; b < BINS; b++) {
            output.writeFloat(m.gun[s][b]); output.writeFloat(m.surf[s][b]);
        }
        for (float value : m.visits) output.writeFloat(value);
        int count = Math.min(sampleLimit, m.samples.size()); output.writeShort(count);
        for (int n = 0; n < count; n++) {
            int index = count <= 1 ? m.samples.size() - 1 : n * (m.samples.size() - 1) / (count - 1);
            float[] sample = m.samples.get(index);
            for (int f = 0; f < FEATURES; f++) output.writeShort((int)Math.round(clamp(0, sample[f], 1) * 65535));
            output.writeShort((int)Math.round(clamp(-1, sample[FEATURES], 1) * 32767));
            output.writeShort((int)Math.round(clamp(0, sample[FEATURES + 1], 1) * 65535));
        }
    }

    private static Snapshot readSnapshot(File file) throws IOException {
        if (file.length() > MAX_SNAPSHOT_BYTES) throw new IOException("Arquivo grande demais");
        Snapshot snapshot = new Snapshot();
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC_V2) throw new IOException("Formato desconhecido");
            int format = input.readInt();
            if (format != 2 && format != 3) throw new IOException("Versao desconhecida");
            snapshot.generation = checkedLong(input.readLong()); snapshot.rounds = checkedLong(input.readLong());
            int count = input.readInt();
            if (count < 0 || count > MAX_MODELS) throw new IOException("Contagem invalida");
            for (int n = 0; n < count; n++) {
                String name = input.readUTF(); Model m = new Model();
                if (name.length() > 512 || snapshot.models.containsKey(name)) throw new IOException("Nome invalido");
                for (int gun = 0; gun < GUNS; gun++) {
                    m.gunHits[gun] = checked(input.readDouble()); m.gunTrials[gun] = checked(input.readDouble());
                    if (m.gunHits[gun] > m.gunTrials[gun] + .001) throw new IOException("Taxa invalida");
                }
                m.enemyWaves = checked(input.readDouble()); m.waveBreaks = checked(input.readDouble());
                m.surfHits = checked(input.readDouble());
                m.shots = checkedLong(input.readLong()); m.hits = checkedLong(input.readLong());
                if (m.hits > m.shots) throw new IOException("Acertos invalidos");
                if (format >= 3) for (int i = 0; i < 12; i++) {
                    m.realAttempts[i] = (float)checked(input.readFloat()); m.realHits[i] = (float)checked(input.readFloat());
                    if (m.realHits[i] > m.realAttempts[i] + .001) throw new IOException("Calibracao invalida");
                }
                for (int s = 0; s < SEGMENTS; s++) for (int b = 0; b < BINS; b++) {
                    m.gun[s][b] = (float)checked(input.readFloat()); m.surf[s][b] = (float)checked(input.readFloat());
                }
                for (int b = 0; b < BINS; b++) m.visits[b] = (float)checked(input.readFloat());
                int samples = input.readUnsignedShort();
                if (samples > MAX_SAMPLES) throw new IOException("Amostras invalidas");
                for (int sample = 0; sample < samples; sample++) {
                    float[] row = new float[FEATURES + 2];
                    for (int f = 0; f < FEATURES; f++) row[f] = input.readUnsignedShort() / 65535f;
                    row[FEATURES] = input.readShort() / 32767f;
                    row[FEATURES + 1] = input.readUnsignedShort() / 65535f;
                    if (row[FEATURES] < -1) throw new IOException("GuessFactor invalido");
                    m.samples.add(row);
                }
                snapshot.models.put(name, m);
            }
            if (input.read() != -1) throw new IOException("Dados extras"); // forca verificacao CRC GZIP
        }
        return snapshot;
    }

    private static long checkedLong(long value) throws IOException {
        if (value < 0 || value > 1000000000000L) throw new IOException("Contador invalido");
        return value;
    }
    private static class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream output) throws IOException { super(output, 8192); def.setLevel(1); }
    }
    private static class Snapshot {
        long generation, rounds;
        final LinkedHashMap<String, Model> models = new LinkedHashMap<String, Model>();
    }

    private static double checked(double value) throws IOException {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 1e8)
            throw new IOException("Estatistica invalida");
        return value;
    }
    private static int segment(double distance, double speed, double wall) {
        int d = distance < 250 ? 0 : distance < 500 ? 1 : 2;
        int v = speed < 2 ? 0 : speed < 6 ? 1 : 2;
        return (d * 3 + v) * 2 + (wall < 90 ? 1 : 0);
    }
    private double wallDistance(Point2D.Double p) {
        return Math.min(Math.min(p.x, width - p.x), Math.min(p.y, height - p.y));
    }
    private boolean inside(Point2D.Double p, double margin) {
        return p.x >= margin && p.y >= margin && p.x <= width - margin && p.y <= height - margin;
    }
    private static double clamp(double low, double value, double high) { return Math.max(low, Math.min(value, high)); }
    private static double sq(double x) { return x * x; }
    private static int sign(double x) { return x < 0 ? -1 : 1; }
    private static double bearing(Point2D.Double from, Point2D.Double to) { return Math.atan2(to.x - from.x, to.y - from.y); }
    private static Point2D.Double project(Point2D.Double p, double angle, double distance) {
        return new Point2D.Double(p.x + Math.sin(angle) * distance, p.y + Math.cos(angle) * distance);
    }
    private static Point2D.Double copy(Point2D.Double p) { return new Point2D.Double(p.x, p.y); }

    @Override public void onPaint(Graphics2D g) {
        if (!DEBUG_PAINT) return;
        g.setColor(new Color(0, 220, 255, 100));
        for (Wave w : incoming) {
            double r = radius(w, now);
            g.drawOval((int)(w.origin.x - r), (int)(w.origin.y - r), (int)(r * 2), (int)(r * 2));
        }
        if (destination != null) {
            g.setColor(Color.GREEN);
            g.drawLine((int)getX(), (int)getY(), (int)destination.x, (int)destination.y);
        }
    }
    private static class Model {
        final float[][] gun = new float[SEGMENTS][BINS], surf = new float[SEGMENTS][BINS];
        final double[] gunHits = new double[GUNS], gunTrials = new double[GUNS];
        final float[] visits = new float[BINS];
        final ArrayList<float[]> samples = new ArrayList<float[]>();
        double enemyWaves, waveBreaks, surfHits;
        long shots, hits, retreatUntil;
        int recoveryMisses;
        final float[] realAttempts = new float[12], realHits = new float[12];
    }
    private static class Enemy {
        final String name; final Model model;
        Point2D.Double p = new Point2D.Double();
        long time = -1, nextPossibleFire, directionChanged, previousTime = -1;
        Point2D.Double previousPoint = new Point2D.Double();
        double focus = .35, rayFocus, chase, orbiting, evasive; long lastConfirmedAim = -1;
        long gfTime = -100, collisionClock = -1; int gfSamples = -1; double cachedGF;
        final Point2D.Double[] collisionPrediction = new Point2D.Double[15];
        double energy = 100, heading, velocity, turn, acceleration, damage, bonus;
        int direction = 1; boolean dead;
        Enemy(String name, Model model) { this.name = name; this.model = model; }
    }
    private static class Wave {
        Point2D.Double origin; long time;
        double speed, power, bearing, confidence;
        int direction, segment; Model model; String name; boolean visited;
    }
    private static class GunWave extends Wave { double[] angles, features; }
    private static class Shot {
        final Model model; final long time; final int bucket; final boolean recovery;
        Shot(Model model, long time, int bucket, boolean recovery) {
            this.model = model; this.time = time; this.bucket = bucket; this.recovery = recovery;
        }
    }
    private static class State {
        final Point2D.Double p; final double heading, velocity;
        State(Point2D.Double p, double heading, double velocity) {
            this.p = p; this.heading = heading; this.velocity = velocity;
        }
    }
}
