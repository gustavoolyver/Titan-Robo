package titan;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.io.*;
import java.util.*;

/**
 * TitanVingation: Robocode CLASSICO, Java 8+, um unico fonte.
 * Projetado para melee (voce + 5 inimigos), com transicao automatica para duelo.
 * Instale em robots/titan/TitanVingation.java. Nao serve para Tank Royale.
 *
 * Sistemas:
 * - radar 360 em melee / trava com overscan em duelo;
 * - movimento minimum-risk com simulacao fisica e wall smoothing;
 * - wave surfing: ondas confiaveis em duelo, estimativas conservadoras em melee;
 * - mira virtual: head-on, linear, circular e GuessFactor segmentado;
 * - escolha de alvo, controle de potencia, protecao contra tiro bloqueado;
 * - estatisticas por adversario persistidas em titan-learning-v1.bin.
 *
 * Aprendizado estatistico, nao rede neural; nao garante vencer qualquer robo.
 * O arquivo de aprendizado fica no diretorio de dados do robo, fora do JAR.
 */
public class TitanVingation extends AdvancedRobot {
    private static final double PI = Math.PI, TWO_PI = 2 * PI;
    private static final int BINS = 31, MID = BINS / 2, SEGMENTS = 18;
    private static final int MAX_MODELS = 16, MAX_WAVES = 100;
    private static final String DATA = "titan-learning-v1.bin";
    private static final int MAGIC = 0x54564D31;
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
    private boolean saved;

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
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        while (true) {
            rememberSelf();
            updateWaves();
            selectTarget();
            controlRadar();
            move();
            aimAndFire();
            execute();
        }
    }

    private Point2D.Double me() { return new Point2D.Double(getX(), getY()); }
    private void rememberSelf() {
        int k = (int)(getTime() % selfHistory.length);
        selfHistory[k] = me(); velocityHistory[k] = getVelocity();
        headingHistory[k] = getHeadingRadians(); historyTime[k] = getTime();
    }

    @Override public void onScannedRobot(ScannedRobotEvent e) {
        long now = getTime(); lastScan = now;
        Point2D.Double self = me();
        double abs = getHeadingRadians() + e.getBearingRadians();
        Enemy en = enemies.get(e.getName());
        if (en == null) {
            en = new Enemy(e.getName(), model(e.getName()));
            enemies.put(e.getName(), en);
        }
        long gap = now - en.time;
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
                incoming.add(w);
                en.nextPossibleFire = fireTime + (long)Math.ceil((1 + drop / 5) / getGunCoolingRate());
            }
        }
        double deltaHeading = en.time >= 0 && gap > 0
            ? Utils.normalRelativeAngle(e.getHeadingRadians() - en.heading) / gap : 0;
        en.turn = clamp(-.16, deltaHeading, .16);
        en.p = project(self, abs, e.getDistance());
        en.heading = e.getHeadingRadians(); en.velocity = e.getVelocity();
        en.energy = e.getEnergy(); en.time = now; en.dead = false;
        en.damage = en.bonus = 0;
        double lat = en.velocity * Math.sin(en.heading - abs);
        if (Math.abs(lat) > .05) en.direction = sign(lat);
        updateGunWaves(en);
    }

    private void selectTarget() {
        Enemy best = null; double bestScore = Double.POSITIVE_INFINITY;
        Point2D.Double self = me();
        for (Enemy e : enemies.values()) {
            if (e.dead || getTime() - e.time > 40) continue;
            double d = self.distance(e.p);
            double score = d * (1 + .012 * Math.min(40, e.energy));
            score *= 1 + .035 * (getTime() - e.time);
            if (e == target) score *= .78; // Evita trocar de alvo a cada tick.
            if (e.energy < 5) score *= .72;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        target = best;
    }

    private void controlRadar() {
        if (getOthers() > 1 || target == null || getTime() - target.time > 3) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        } else {
            double error = Utils.normalRelativeAngle(bearing(me(), target.p) - getRadarHeadingRadians());
            double extra = Math.min(.35, Math.atan(36 / Math.max(40, me().distance(target.p))));
            setTurnRadarRightRadians(error + sign(error) * extra);
        }
        if (getTime() - lastScan > 15) setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    private void move() {
        if (getOthers() == 1 && target != null && getTime() - target.time < 8) {
            surf(); return;
        }
        // Replaneja regularmente e quando a posicao escolhida fica perigosa.
        if (destination == null || getTime() - lastPlan >= 5 || me().distance(destination) < 35) {
            planMelee(); lastPlan = getTime();
        }
        goTo(destination);
    }

    private void planMelee() {
        Point2D.Double self = me(), best = null;
        double bestRisk = Double.POSITIVE_INFINITY;
        int candidates = skipped > 3 ? 20 : 36;
        for (int i = 0; i < candidates; i++) {
            double angle = TWO_PI * i / candidates;
            double distance = i % 2 == 0 ? 150 : 235;
            Point2D.Double p = project(self, angle, distance);
            p.x = clamp(36, p.x, width - 36); p.y = clamp(36, p.y, height - 36);
            double risk = positionRisk(p, self);
            // Testa a rota, nao apenas o ponto final.
            State s = new State(self, getHeadingRadians(), getVelocity());
            for (int t = 1; t <= 24; t++) {
                s = step(s, bearing(s.p, p), 8, 0);
                if (t % 4 == 0) {
                    risk += .10 * positionRisk(s.p, self);
                    for (Wave w : incoming) {
                        double separation = Math.abs(w.origin.distance(s.p) - radius(w, getTime() + t));
                        if (separation < 32) risk += waveDanger(w, s.p) * w.confidence * 3;
                    }
                }
            }
            if (risk < bestRisk) { bestRisk = risk; best = p; }
        }
        previousDestination = destination; destination = best;
    }

    private double positionRisk(Point2D.Double p, Point2D.Double start) {
        double risk = 1400 / sq(Math.max(25, wallDistance(p)));
        double centerDistance = p.distance(width / 2, height / 2);
        risk += .10 * (1 - Math.min(1, centerDistance / Math.hypot(width / 2, height / 2)));
        for (Enemy e : enemies.values()) {
            if (e.dead || getTime() - e.time > 45) continue;
            Point2D.Double ep = predict(e, Math.min(8, getTime() - e.time), false);
            double d = Math.max(36, p.distance(ep));
            double confidence = Math.max(.3, 1 - (getTime() - e.time) / 60.0);
            risk += confidence * (16000 + 100 * Math.min(80, e.energy)) / sq(d);
            if (d < 125) risk += (125 - d) / 20;
            // Deslocamento lateral ao inimigo reduz tiros head-on/lineares.
            double travel = bearing(start, p), enemyAngle = bearing(ep, start);
            risk += .10 * sq(Math.cos(travel - enemyAngle)) * confidence;
        }
        if (previousDestination != null)
            risk += 450 / sq(Math.max(45, p.distance(previousDestination)));
        return risk;
    }

    private void surf() {
        Point2D.Double self = me();
        Wave nearest = null; double soonest = Double.POSITIVE_INFINITY;
        for (Wave w : incoming) {
            double remaining = (self.distance(w.origin) - radius(w, getTime())) / w.speed;
            if (remaining > -2 && remaining < soonest) { nearest = w; soonest = remaining; }
        }
        if (nearest == null) {
            double distance = self.distance(target.p);
            if (target.energy < .1 && incoming.isEmpty()) {
                goTo(project(target.p, bearing(target.p, self), 85)); return;
            }
            double a = bearing(target.p, self) + orbit * (PI / 2 + clamp(-.4, (distance - 420) / 420, .5));
            goAngle(smooth(self, a, orbit), 8); return;
        }
        double left = surfRisk(nearest, -1, 8);
        double right = surfRisk(nearest, 1, 8);
        double stop = surfRisk(nearest, orbit, 0);
        int direction = left < right ? -1 : 1;
        double speed = 8;
        if (stop < Math.min(left, right) * .85 && soonest > 4) { direction = orbit; speed = 0; }
        orbit = direction;
        double distance = self.distance(nearest.origin);
        double angle = bearing(nearest.origin, self) + direction *
            (PI / 2 + clamp(-.45, (distance - 400) / 400, .50));
        goAngle(smooth(self, angle, direction), speed);
    }

    private double surfRisk(Wave main, int direction, double maxSpeed) {
        State state = new State(me(), getHeadingRadians(), getVelocity());
        double risk = 0; boolean crossed = false;
        int limit = skipped > 3 ? 65 : 100;
        for (int tick = 1; tick <= limit; tick++) {
            double distance = state.p.distance(main.origin);
            double angle = bearing(main.origin, state.p) + direction *
                (PI / 2 + clamp(-.45, (distance - 400) / 400, .50));
            state = step(state, angle, maxSpeed, direction);
            for (Wave w : incoming) {
                double before = radius(w, getTime() + tick - 1);
                double after = before + w.speed;
                double d = w.origin.distance(state.p);
                if (d >= before - 18 && d <= after + 18) {
                    risk += waveDanger(w, state.p) * w.confidence / Math.sqrt(tick + 1);
                    if (w == main) crossed = true;
                }
            }
            risk += .0003 * 900 / sq(Math.max(18, wallDistance(state.p)));
            if (crossed) break;
        }
        if (target != null && state.p.distance(target.p) < 130) risk += .8;
        return risk + .00001;
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
        if (p == null) { setAhead(100); setTurnRight(20); return; }
        double distance = me().distance(p);
        double relative = Utils.normalRelativeAngle(bearing(me(), p) - getHeadingRadians());
        int direction = 1;
        if (Math.abs(relative) > PI / 2) { relative = Utils.normalRelativeAngle(relative + PI); direction = -1; }
        setTurnRightRadians(relative); setMaxVelocity(Math.abs(relative) > 1.1 ? 5 : 8);
        setAhead(direction * distance);
    }

    private void goAngle(double angle, double speed) {
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
        if (target == null || getTime() - target.time > 10) return;
        Point2D.Double self = me();
        double distance = self.distance(target.p);
        double power = choosePower(target, distance);
        double[] angles = gunAngles(target, self, power);
        int selected = 2; // Circular antes de existir evidencia suficiente.
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < angles.length; i++) {
            double prior = i == 2 ? .28 : (i == 1 ? .25 : .20);
            double score = (target.model.gunHits[i] + 8 * prior) / (target.model.gunTrials + 8);
            if (score > best) { best = score; selected = i; }
        }
        if (Math.abs(target.velocity) < .01) selected = 0;
        double aim = angles[selected];
        setTurnGunRightRadians(Utils.normalRelativeAngle(aim - getGunHeadingRadians()));
        // Ondas virtuais aprendem mesmo quando o canhao esta resfriando.
        if (getTime() - lastVirtual >= 5 && getTime() == target.time && outgoing.size() < MAX_WAVES) {
            GunWave w = new GunWave();
            w.origin = self; w.time = getTime(); w.speed = 20 - 3 * power;
            w.bearing = bearing(self, target.p); w.direction = target.direction;
            w.segment = segment(distance, Math.abs(target.velocity), wallDistance(target.p));
            w.angles = angles; w.model = target.model; w.name = target.name;
            outgoing.add(w); lastVirtual = getTime();
        }
        double error = Math.abs(Utils.normalRelativeAngle(aim - getGunHeadingRadians()));
        double tolerance = Math.atan(15 / Math.max(30, distance));
        if (getGunHeat() == 0 && getEnergy() > power + .12
                && getTime() - target.time <= (getOthers() > 1 ? 5 : 1)
                && error < tolerance && !blocked(self, aim, distance)) {
            setFireBullet(power);
        }
    }

    private double choosePower(Enemy e, double distance) {
        double power = distance < 150 ? 3 : distance < 330 ? 2.5 : distance < 550 ? 1.9 : 1.2;
        if (getOthers() > 1 && distance > 350) power = Math.min(power, 1.7);
        if (getEnergy() < 20) power = Math.min(power, 1.25);
        if (getEnergy() < 8) power = Math.min(power, .55);
        double kill = e.energy <= 4 ? e.energy / 4 : (e.energy + 2) / 6;
        power = Math.min(power, kill + .03);
        return clamp(.1, Math.min(power, getEnergy() / 6), 3);
    }

    private double[] gunAngles(Enemy e, Point2D.Double self, double power) {
        double speed = 20 - 3 * power;
        double direct = bearing(self, e.p);
        Point2D.Double linear = intercept(e, self, speed, false);
        Point2D.Double circular = intercept(e, self, speed, true);
        int seg = segment(self.distance(e.p), Math.abs(e.velocity), wallDistance(e.p));
        double best = -1; int index = MID;
        for (int i = 0; i < BINS; i++) {
            double value = histogram(e.model.gun, seg, i);
            // Prior leve de mira frontal para dados ainda escassos.
            value += .15 / (1 + sq((i - MID) / 4.0));
            if (value > best) { best = value; index = i; }
        }
        double gfAngle = direct + e.direction * ((index - MID) / (double)MID) * Math.asin(8 / speed);
        return new double[] {direct, bearing(self, linear), bearing(self, circular), gfAngle};
    }

    private Point2D.Double intercept(Enemy e, Point2D.Double self, double speed, boolean circular) {
        Point2D.Double p = predict(e, Math.min(10, getTime() - e.time), circular);
        double h = e.heading + (circular ? e.turn * Math.min(10, getTime() - e.time) : 0);
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
            if (e == target || e.dead || getTime() - e.time > 12) continue;
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
            double distance = w.origin.distance(e.p), r = radius(w, getTime());
            if (r >= distance - 12) {
                // Scans atrasados em melee reduzem a confiabilidade da amostra.
                double weight = r > distance + 50 ? .15 : 1;
                int bin = factorIndex(w, e.p);
                learn(w.model.gun[w.segment], bin, weight);
                double actual = bearing(w.origin, e.p);
                double tolerance = Math.atan(20 / Math.max(30, distance));
                w.model.gunTrials = w.model.gunTrials * .995 + weight;
                for (int i = 0; i < 4; i++) {
                    double error = Math.abs(Utils.normalRelativeAngle(actual - w.angles[i]));
                    w.model.gunHits[i] = w.model.gunHits[i] * .995 +
                        weight * Math.exp(-.5 * sq(error / Math.max(.015, tolerance)));
                }
                it.remove();
            }
        }
    }

    private void updateWaves() {
        Point2D.Double self = me();
        Iterator<Wave> it = incoming.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            if (radius(w, getTime()) > w.origin.distance(self) + 65 || getTime() - w.time > 180) it.remove();
        }
        Iterator<GunWave> guns = outgoing.iterator();
        while (guns.hasNext()) {
            GunWave w = guns.next(); Enemy e = enemies.get(w.name);
            if (getTime() - w.time > 160 || e == null || e.dead) guns.remove();
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
        return (1 + w.power / 6) * (.12 + prior * .28 + learned / sum * BINS);
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
        Enemy shooter = enemies.get(e.getName());
        if (shooter != null) shooter.bonus += 3 * e.getPower();
        matchWave(e.getName(), e.getBullet(), me(), true);
        destination = null;
    }
    @Override public void onBulletHitBullet(BulletHitBulletEvent e) {
        Bullet bullet = e.getHitBullet();
        matchWave(bullet.getName(), bullet, new Point2D.Double(bullet.getX(), bullet.getY()), false);
    }
    private void matchWave(String name, Bullet bullet, Point2D.Double hit, boolean damage) {
        Wave best = null; double error = Double.POSITIVE_INFINITY;
        for (Wave w : incoming) {
            if (!w.name.equals(name) || Math.abs(w.speed - bullet.getVelocity()) > .15) continue;
            double difference = Math.abs(w.origin.distance(hit) - radius(w, getTime()));
            if (difference < error) { error = difference; best = w; }
        }
        if (best != null && error < (getOthers() == 1 ? 50 : 110)) {
            learn(best.model.surf[best.segment], factorIndex(best, hit), damage ? 1 : .35);
            incoming.remove(best);
        }
    }
    @Override public void onBulletHit(BulletHitEvent e) {
        Enemy victim = enemies.get(e.getName());
        if (victim != null) victim.damage += Rules.getBulletDamage(e.getBullet().getPower());
    }
    @Override public void onRobotDeath(RobotDeathEvent e) {
        Enemy en = enemies.get(e.getName()); if (en != null) en.dead = true;
        if (target == en) target = null;
        destination = null;
        // Tiros ja disparados pelo inimigo continuam ativos apos a morte dele.
    }
    @Override public void onHitWall(HitWallEvent e) { orbit = -orbit; destination = null; }
    @Override public void onHitRobot(HitRobotEvent e) { orbit = -orbit; destination = null; }
    @Override public void onSkippedTurn(SkippedTurnEvent e) { skipped++; }
    @Override public void onWin(WinEvent e) { saveLearning(); }
    @Override public void onDeath(DeathEvent e) { saveLearning(); }
    @Override public void onRoundEnded(RoundEndedEvent e) { saveLearning(); }

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

    private void loadLearning() {
        if (loaded) return;
        loaded = true;
        File file = getDataFile(DATA);
        if (file == null || !file.isFile()) return;
        LinkedHashMap<String, Model> restored = new LinkedHashMap<String, Model>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Formato desconhecido");
            int count = in.readInt();
            if (count < 0 || count > MAX_MODELS) throw new IOException("Contagem invalida");
            for (int n = 0; n < count; n++) {
                String name = in.readUTF(); Model m = new Model();
                m.gunTrials = checked(in.readDouble());
                for (int i = 0; i < 4; i++) m.gunHits[i] = checked(in.readDouble());
                for (int s = 0; s < SEGMENTS; s++) for (int b = 0; b < BINS; b++) {
                    m.gun[s][b] = (float)checked(in.readFloat());
                    m.surf[s][b] = (float)checked(in.readFloat());
                }
                restored.put(name, m);
            }
            MEMORY.putAll(restored);
            out.println("Aprendizado carregado: " + restored.size() + " adversarios.");
        } catch (IOException ex) { out.println("Aprendizado nao carregado: " + ex.getMessage()); }
    }

    private void saveLearning() {
        if (saved) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream outData = new DataOutputStream(bytes);
            outData.writeInt(MAGIC); outData.writeInt(MEMORY.size());
            for (Map.Entry<String, Model> entry : MEMORY.entrySet()) {
                outData.writeUTF(entry.getKey()); Model m = entry.getValue();
                outData.writeDouble(m.gunTrials);
                for (double score : m.gunHits) outData.writeDouble(score);
                for (int s = 0; s < SEGMENTS; s++) for (int b = 0; b < BINS; b++) {
                    outData.writeFloat(m.gun[s][b]); outData.writeFloat(m.surf[s][b]);
                }
            }
            outData.close();
            File file = getDataFile(DATA);
            if (file == null) throw new IOException("Diretorio indisponivel");
            if (bytes.size() > getDataQuotaAvailable() + file.length()) throw new IOException("Sem cota para salvar");
            try (OutputStream stream = new RobocodeFileOutputStream(file)) { bytes.writeTo(stream); }
            saved = true;
            out.println("Aprendizado salvo: " + bytes.size() + " bytes; skipped turns: " + skipped);
        } catch (IOException ex) { out.println("Falha ao salvar aprendizado: " + ex.getMessage()); }
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
            double r = radius(w, getTime());
            g.drawOval((int)(w.origin.x - r), (int)(w.origin.y - r), (int)(r * 2), (int)(r * 2));
        }
        if (destination != null) {
            g.setColor(Color.GREEN);
            g.drawLine((int)getX(), (int)getY(), (int)destination.x, (int)destination.y);
        }
    }
    private static class Model {
        final float[][] gun = new float[SEGMENTS][BINS], surf = new float[SEGMENTS][BINS];
        final double[] gunHits = new double[4];
        double gunTrials;
    }
    private static class Enemy {
        final String name; final Model model;
        Point2D.Double p = new Point2D.Double();
        long time = -1, nextPossibleFire;
        double energy = 100, heading, velocity, turn, damage, bonus;
        int direction = 1; boolean dead;
        Enemy(String name, Model model) { this.name = name; this.model = model; }
    }
    private static class Wave {
        Point2D.Double origin; long time;
        double speed, power, bearing, confidence;
        int direction, segment; Model model; String name;
    }
    private static class GunWave extends Wave { double[] angles; }
    private static class State {
        final Point2D.Double p; final double heading, velocity;
        State(Point2D.Double p, double heading, double velocity) {
            this.p = p; this.heading = heading; this.velocity = velocity;
        }
    }
}
