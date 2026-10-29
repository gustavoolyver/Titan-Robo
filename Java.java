package campeao;

import robocode.*;
import robocode.util.Utils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Campeao - robo de duelo 1x1 (AdvancedRobot).
 *
 *  - Radar: lock estreito (oscilante) no unico oponente.
 *  - Movimento: "wave surfing" com simulacao precisa da fisica do Robocode e
 *    antecipacao do proximo tiro inimigo (onda virtual).
 *  - Canhao: interceptacao exata (quando o modelo do oponente e valido) +
 *    GuessFactor adaptativo (kNN) como reserva; escolha por desempenho virtual.
 */
public class ChimpazeComSarna extends AdvancedRobot {

    // ===================== constantes =====================
    static final double MAX_VEL = 8.0;
    static final double HALF = 18.0;
    static final double WALL_SAFE = 20.0;
    static final double STICK = 160.0;
    static final int HN = 128;
    static final int HM = HN - 1;
    static final int GF_BINS = 61;
    static final int HMAX = 80;
    static final int VJN_MAX = 12;
    static final boolean DEBUG = true;

    // ===================== parametros ajustaveis =====================
    static double DESIRED_DIST = 280;
    static double POWER_NEAR = 3.0, POWER_MID = 3.0, POWER_FAR = 2.5;

    // ===================== estado estatico (persiste entre rounds) =====================
    static ArrayList<double[]> gSamples = new ArrayList<double[]>();
    static final int NF = 7;
    static final double[] FW = {1.5, 2.0, 1.0, 1.0, 1.0, 0.8, 0.8};
    static final int KNN = 30;

    static double[][] surfStats = new double[6][GF_BINS];
    static double modelTrust = -1;
    static int modelOk = 0, modelBad = 0;
    static int sOurShots = 0, sOurHits = 0, sEnShots = 0, sEnHits = 0, sRounds = 0, sSkipped = 0, sBlind = 0;
    static long sMaxNs = 0, sSumNs = 0, sTicks = 0;
    static double scoreK = 0.6, scoreN = 0.4;
    static int vShotsK = 0, vHitsK = 0, vHitsN = 0;
    static double kErrAvg = 0; static int kErrN = 0;
    static boolean kaderLike = false;

    // ===================== estado por round =====================
    double bfW, bfH;
    long now;
    double myX, myY, myHeading, myVel, myEnergy, gunHeading, radarHeading;

    boolean hasEnemy;
    String eName = "";
    double eX, eY, eHeading, eVel, eEnergy, eDist, eAbs, ePrevVel, eLat, eAdv;
    int eDir = 1;
    long eDirChange = 0;
    long scanTime = -1;
    long lastFreshTime = -1;
    double sBearing, sDist, sHeading, sVel, sEnergy;

    double lastEEnergy = 100;
    double eGunHeat = 3.0;
    double bonusTick = 0, dmgTick = 0;

    long[] hT = new long[HN];
    double[] hMX = new double[HN], hMY = new double[HN], hMH = new double[HN], hMV = new double[HN];
    double[] hEX = new double[HN], hEY = new double[HN], hEH = new double[HN], hEV = new double[HN];
    boolean[] hEValid = new boolean[HN];

    int orbitDir = 1;
    boolean kader = false;
    double kDir = 2;
    double[] kPred = null, kPredAlt = null;
    long kPredTime = -1;

    List<EWave> eWaves = new ArrayList<EWave>();
    List<GWave> gWaves = new ArrayList<GWave>();
    Pending pending = null;
    int roundShots = 0, roundHits = 0, roundEnShots = 0, roundEnHits = 0;
    double roundMinEnergy = 100;
    double lastBestDanger = 0;

    // buffers de simulacao
    final double[] px = new double[HMAX + 8], py = new double[HMAX + 8];
    final double[] ph = new double[HMAX + 8], pv = new double[HMAX + 8];
    final double[] kx = new double[HMAX + 8], ky = new double[HMAX + 8];
    final double[] kTmp = new double[6];
    int simH = 0;
    boolean simHasK = false;
    final EWave vw = new EWave();

    // ===================== classes auxiliares =====================
    static class EWave {
        double sx, sy, speed, power, ref, mea, modelAngle = Double.NaN;
        long fireTime;
        int dir, seg;
        double firstDanger = -1, lastDanger = -1, detDist;
    }

    static class GWave {
        double sx, sy, speed, ref, mea, aimK = Double.NaN, aimN;
        long fireTime;
        int dir;
        double[] feat;
    }

    static class Pending {
        double aim, aimK, aimN, power, ref, mea;
        int dir;
        double[] feat;
    }

    // ===================== laco principal =====================
    public void run() {
        setColors(new Color(20, 20, 20), new Color(200, 30, 30), new Color(240, 200, 40));
        setBulletColor(new Color(255, 120, 0));
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        bfW = getBattleFieldWidth();
        bfH = getBattleFieldHeight();
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        while (true) {
            long t0 = System.nanoTime();
            tick();
            long dt = System.nanoTime() - t0;
            sSumNs += dt; sTicks++;
            if (dt > sMaxNs) sMaxNs = dt;
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        scanTime = getTime();
        sBearing = e.getBearingRadians();
        sDist = e.getDistance();
        sHeading = e.getHeadingRadians();
        sVel = e.getVelocity();
        sEnergy = e.getEnergy();
        eName = e.getName();
    }

    public void onBulletHit(BulletHitEvent e) {
        dmgTick += Rules.getBulletDamage(e.getBullet().getPower());
        sOurHits++; roundHits++;
    }

    public void onHitRobot(HitRobotEvent e) {
        dmgTick += 0.6;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        bonusTick += 3 * e.getBullet().getPower();
        sEnHits++; roundEnHits++;
        EWave best = null; double bestErr = 1e9;
        for (EWave w : eWaves) {
            if (Math.abs(w.power - e.getBullet().getPower()) > 0.011) continue;
            double r = w.speed * (getTime() - w.fireTime + 1);
            double d = Math.hypot(getX() - w.sx, getY() - w.sy);
            double err = Math.abs(r - d);
            if (err < bestErr) { bestErr = err; best = w; }
        }
        if (best != null && bestErr < 40) {
            double h = e.getBullet().getHeadingRadians();
            double off = Utils.normalRelativeAngle(h - best.ref);
            double gf = clamp(-1, off / best.mea * best.dir, 1);
            addSurf(best.seg, gf, 1.0);
            if (!Double.isNaN(best.modelAngle)) {
                double err = Math.abs(Utils.normalRelativeAngle(h - best.modelAngle));
                if (err < 0.03) modelOk++; else modelBad++;
                if (DEBUG) System.out.printf("  [HIT-ME] t=%d d=%.0f flight=%d modelErr=%.4f vel=%.1f pos=(%.0f,%.0f) wallMin=%.0f eVel=%.1f firstDng=%.2f lastDng=%.2f detD=%.0f%n", getTime(), Math.hypot(getX() - best.sx, getY() - best.sy), getTime() - best.fireTime, err, getVelocity(), getX(), getY(), Math.min(Math.min(getX(), bfW - getX()), Math.min(getY(), bfH - getY())), eVel, best.firstDanger, best.lastDanger, best.detDist);
                if (modelBad >= 2 && modelBad > modelOk) modelTrust = 0;
            }
            eWaves.remove(best);
        }
    }

    public void onBulletHitBullet(BulletHitBulletEvent e) {
        Bullet hb = e.getHitBullet();
        EWave best = null; double bestErr = 1e9;
        for (EWave w : eWaves) {
            if (Math.abs(w.power - hb.getPower()) > 0.011) continue;
            double r = w.speed * (getTime() - w.fireTime + 1);
            double d = Math.hypot(hb.getX() - w.sx, hb.getY() - w.sy);
            double err = Math.abs(r - d);
            if (err < bestErr) { bestErr = err; best = w; }
        }
        if (best != null && bestErr < 40) eWaves.remove(best);
    }

    public void onSkippedTurn(SkippedTurnEvent e) {
        sSkipped++;
        if (DEBUG) System.out.println("  [SKIPPED TURN] t=" + getTime());
    }

    public void onRoundEnded(RoundEndedEvent e) {
        sRounds++;
        if (DEBUG) {
            System.out.printf("  [GUN] K virt hits=%d/%d  kNN virt hits=%d  scoreK=%.2f scoreN=%.2f | kErrAvg=%.3f kaderLike=%b%n", vHitsK, vShotsK, vHitsN, scoreK, scoreN, kErrAvg, kaderLike);
            System.out.printf("[R%d] %s ticks=%d  eu=%.1f ini=%.1f | tiros eu %d/%d | tiros ini %d acertos %d | minE=%.0f skip=%d blind=%d | modelo ok=%d bad=%d trust=%.0f | cpu med=%.2fms max=%.2fms%n",
                getRoundNum() + 1, (getEnergy() > 0 && getOthers() == 0) ? "VITORIA" : "derrota ", getTime(), getEnergy(), eEnergy, roundHits, roundShots, roundEnShots, roundEnHits, roundMinEnergy, sSkipped, sBlind,
                modelOk, modelBad, modelTrust, sTicks > 0 ? sSumNs / 1e6 / sTicks : 0, sMaxNs / 1e6);
        }
    }

    // ===================== processamento por tick =====================
    void tick() {
        now = getTime();
        myX = getX(); myY = getY(); myHeading = getHeadingRadians(); myVel = getVelocity();
        myEnergy = getEnergy(); gunHeading = getGunHeadingRadians(); radarHeading = getRadarHeadingRadians();
        if (myEnergy < roundMinEnergy) roundMinEnergy = myEnergy;
        boolean fresh = (scanTime == now);
        if (fresh) updateEnemy(); else if (hasEnemy) sBlind++;
        record(fresh);
        if (!hasEnemy) { setTurnRadarRightRadians(Double.POSITIVE_INFINITY); return; }
        if (fresh) {
            trackKader();
            detectEnemyShot();
            lastFreshTime = now;
        } else {
            eGunHeat = Math.max(0, eGunHeat - 0.1);
        }
        updateEnemyWaves();
        doMovement();
        doGun(fresh);
        doRadar(fresh);
        bonusTick = 0; dmgTick = 0;
    }

    void updateEnemy() {
        eAbs = Utils.normalAbsoluteAngle(sBearing + myHeading);
        eDist = sDist;
        eX = myX + Math.sin(eAbs) * eDist;
        eY = myY + Math.cos(eAbs) * eDist;
        ePrevVel = hasEnemy ? eVel : sVel;
        eHeading = sHeading; eVel = sVel; eEnergy = sEnergy;
        eLat = eVel * Math.sin(eHeading - eAbs);
        eAdv = -eVel * Math.cos(eHeading - eAbs);
        if (Math.abs(eLat) > 0.05) {
            int nd = eLat > 0 ? 1 : -1;
            if (nd != eDir) { eDir = nd; eDirChange = now; }
        }
        if (!hasEnemy) {
            hasEnemy = true;
            lastEEnergy = eEnergy;
            kader = eName.toLowerCase().contains("kader");
            if (kader) kaderLike = true;
            if (modelTrust < 0) modelTrust = kader ? 1.0 : 0.0;
        }
    }

    void record(boolean fresh) {
        int i = (int) (now & HM);
        hT[i] = now; hMX[i] = myX; hMY[i] = myY; hMH[i] = myHeading; hMV[i] = myVel;
        hEValid[i] = fresh;
        if (fresh) { hEX[i] = eX; hEY[i] = eY; hEH[i] = eHeading; hEV[i] = eVel; }
    }

    boolean hasMe(long t) { return hT[(int) (t & HM)] == t; }

    boolean hasEn(long t) { int i = (int) (t & HM); return hT[i] == t && hEValid[i]; }

    // ===================== simulador do movimento do oponente (modelo exato do Kader) =====================
    /** k = {x, y, heading, velocity, turnRemaining, dir}; (ux,uy,uh,uv) = meu estado visto pelo scan dele. */
    void kStep(double[] k, double ux, double uy, double uh, double uv) {
        double dx = ux - k[0], dy = uy - k[1];
        double d = Math.sqrt(dx * dx + dy * dy);
        double absB = Math.atan2(dx, dy);
        double cmdTurn;
        if (d > 150) {
            double latV = uv * Math.sin(uh - absB);
            cmdTurn = Utils.normalRelativeAngle(absB - k[2] + latV / k[3]);
        } else {
            double bearingDeg = Math.toDegrees(Utils.normalRelativeAngle(absB - k[2]));
            cmdTurn = Math.toRadians(90 + bearingDeg);
        }
        double ahead = (d - 120) * k[5];
        k[4] = Double.isNaN(cmdTurn) ? 0 : cmdTurn;
        double maxTurn = Math.toRadians(10 - 0.75 * Math.abs(k[3]));
        double t = k[4] > 0 ? Math.min(k[4], maxTurn) : Math.max(k[4], -maxTurn);
        k[4] -= t; k[2] += t;
        k[3] = newVelocity(k[3], ahead);
        double nx = k[0] + k[3] * Math.sin(k[2]), ny = k[1] + k[3] * Math.cos(k[2]);
        boolean hit = false;
        double fx = 0, fy = 0;
        if (nx < HALF) { hit = true; fx = HALF - nx; } else if (nx > bfW - HALF) { hit = true; fx = (bfW - HALF) - nx; }
        if (ny < HALF) { hit = true; fy = HALF - ny; } else if (ny > bfH - HALF) { hit = true; fy = (bfH - HALF) - ny; }
        if (hit) {
            double hm = k[2] % (Math.PI / 2);
            if (Math.abs(hm) > 1e-12) {
                double th = Math.tan(k[2]);
                if (fx == 0) fx = fy * th; else if (fy == 0) fy = fx / th;
            }
            nx += fx; ny += fy;
            k[3] = 0; k[5] = -k[5];
        }
        k[0] = nx; k[1] = ny;
    }

    boolean kSimOn() {
        return kaderLike && (kErrN < 25 || kErrAvg < 1.2);
    }

    /** compara a previsao de 1 passo feita no tick anterior; detecta inversao de direcao; faz nova previsao. */
    void trackKader() {
        if (!kaderLike && now > 60) return;           // para nao-Kader: valida so no inicio
        boolean wall = eX <= HALF + 1e-6 || eX >= bfW - HALF - 1e-6 || eY <= HALF + 1e-6 || eY >= bfH - HALF - 1e-6;
        boolean wallContact = wall && Math.abs(eVel) < 1e-9 && Math.abs(ePrevVel) > 1e-9;
        boolean enabled = eEnergy > 0.001 && lastEEnergy > 0.001;
        if (kPred != null && kPredTime == now - 1 && enabled && (Math.abs(ePrevVel) > 1e-9 || Math.abs(eVel) > 1e-9)) {
            double err = Math.hypot(kPred[0] - eX, kPred[1] - eY);
            double errAlt = Math.hypot(kPredAlt[0] - eX, kPredAlt[1] - eY);
            if (err > 0.5 && !wallContact && errAlt < 0.05) kDir = -kDir;
            kErrAvg = kErrN == 0 ? Math.min(err, 5) : 0.97 * kErrAvg + 0.03 * Math.min(err, 5);
            kErrN++;
            if (!kader && !kaderLike && kErrN >= 25 && kErrAvg < 0.35) kaderLike = true;   // identifica por comportamento
        }
        if (wallContact) kDir = -kDir;
        double[] a = new double[]{eX, eY, eHeading, eVel, 0, kDir};
        double[] b = new double[]{eX, eY, eHeading, eVel, 0, -kDir};
        kStep(a, myX, myY, myHeading, myVel);
        kStep(b, myX, myY, myHeading, myVel);
        kPred = a; kPredAlt = b; kPredTime = now;
    }

    // ===================== deteccao de tiros inimigos =====================
    void detectEnemyShot() {
        double heatBefore = eGunHeat;
        eGunHeat = Math.max(0, eGunHeat - 0.1);
        double expected = lastEEnergy + bonusTick - dmgTick;
        double drop = expected - eEnergy;
        lastEEnergy = eEnergy;
        if (drop < 0.095) return;

        boolean atWall = eX <= HALF + 1.0 || eX >= bfW - HALF - 1.0 || eY <= HALF + 1.0 || eY >= bfH - HALF - 1.0;
        boolean wallContact = atWall && Math.abs(eVel) < 0.01 && Math.abs(ePrevVel) > 0.5;
        boolean canFire = heatBefore <= 0.02;
        double power = drop;
        if (wallContact) {
            double wd = Math.max(0, Math.min(8, Math.abs(ePrevVel) + 1) / 2 - 1);
            double wd2 = Math.max(0, Math.abs(ePrevVel) / 2 - 1);
            if (!canFire) return;
            double rest = drop - wd;
            double rest2 = drop - wd2;
            if (Math.abs(drop - wd) < 0.12 || Math.abs(drop - wd2) < 0.12) {
                power = drop;
            } else if (rest >= 0.095 && rest <= 3.001) power = rest;
            else if (rest2 >= 0.095 && rest2 <= 3.001) power = rest2;
        }
        if (power > 3.001 || power < 0.095) return;
        power = Math.min(3.0, Math.max(0.1, power));
        eGunHeat = 1 + power / 5 - 0.1;
        createEnemyWave(power);
    }

    void createEnemyWave(double power) {
        if (!hasMe(now - 1) || !hasEn(now - 1)) return;
        int i1 = (int) ((now - 1) & HM);
        EWave w = new EWave();
        w.sx = hEX[i1]; w.sy = hEY[i1];
        w.fireTime = now;
        w.power = power; w.speed = 20 - 3 * power;
        w.ref = Math.atan2(hMX[i1] - w.sx, hMY[i1] - w.sy);
        double lat = hMV[i1] * Math.sin(hMH[i1] - w.ref);
        w.dir = lat >= 0 ? 1 : -1;
        w.mea = Math.asin(MAX_VEL / w.speed);
        double dist = Math.hypot(hMX[i1] - w.sx, hMY[i1] - w.sy);
        int ds = dist < 200 ? 0 : (dist < 350 ? 1 : 2);
        int ls = Math.abs(lat) < 2 ? 0 : 1;
        w.seg = ds * 2 + ls;
        if (hasMe(now - 2) && hasEn(now - 2)) {
            int i2 = (int) ((now - 2) & HM);
            w.modelAngle = modelAim(hMX[i2], hMY[i2], hMH[i2], hMV[i2], hEX[i2], hEY[i2]);
        }
        w.detDist = dist;
        eWaves.add(w);
        sEnShots++; roundEnShots++;
    }

    /** angulo de disparo do Kader dado o meu estado e a posicao dele no instante T-2. */
    static double modelAim(double mx, double my, double mh, double mv, double ex, double ey) {
        double th = Math.atan2(mx - ex, my - ey);
        double lv = mv * Math.sin(mh - th);
        double d = Math.hypot(mx - ex, my - ey);
        double k = d > 150 ? 1.0 / 22 : 1.0 / 15;
        return th + k * lv;
    }

    void updateEnemyWaves() {
        for (Iterator<EWave> it = eWaves.iterator(); it.hasNext();) {
            EWave w = it.next();
            double r = w.speed * (now - w.fireTime + 1);
            double d = Math.hypot(myX - w.sx, myY - w.sy);
            if (r > d + 60) it.remove();
        }
    }

    // ===================== estatisticas de surf (reserva aprendida) =====================
    static void addSurf(int seg, double gf, double w) {
        double[] s = surfStats[seg];
        double c = (gf + 1) / 2 * (GF_BINS - 1);
        for (int b = 0; b < GF_BINS; b++) {
            double z = (b - c) / 2.0;
            s[b] += w * Math.exp(-0.5 * z * z);
        }
    }

    double learnedDanger(EWave w, double gfLo, double gfHi) {
        double[] s = surfStats[w.seg];
        double tot = 0, in = 0;
        for (int b = 0; b < GF_BINS; b++) tot += s[b];
        if (tot < 1e-9) return 0;
        for (int b = 0; b < GF_BINS; b++) {
            double g = -1 + 2.0 * b / (GF_BINS - 1);
            if (g >= gfLo - 0.02 && g <= gfHi + 0.02) in += s[b];
        }
        return in / tot;
    }

    // ===================== movimento: surfing com antecipacao =====================
    static final int[][] PLANS;
    static {
        ArrayList<int[]> pl = new ArrayList<int[]>();
        int[] dirs = {1, -1, 0};
        int[] sw = {2, 4, 6, 9, 13};
        for (int d1 : dirs) {
            pl.add(new int[]{d1, 999, d1});
            for (int s : sw) for (int d2 : dirs) if (d2 != d1) pl.add(new int[]{d1, s, d2});
        }
        PLANS = pl.toArray(new int[0][]);
    }

    void doMovement() {
        List<EWave> act = new ArrayList<EWave>();
        for (EWave w : eWaves) {
            double r = w.speed * (now - w.fireTime + 1);
            double d = Math.hypot(myX - w.sx, myY - w.sy);
            if (r < d + 30) act.add(w);
        }
        boolean useK = kSimOn();
        int jn = -1;
        if (useK && modelTrust > 0 && eEnergy > 0.001) {
            jn = 1 + (int) Math.ceil(eGunHeat / 0.1 - 1e-9);
            if (jn > VJN_MAX) jn = -1;
        }
        int[] chosen = null;
        if (!act.isEmpty() || jn > 0) {
            int H = 0;
            for (EWave w : act) {
                double d = Math.hypot(myX - w.sx, myY - w.sy);
                double r = w.speed * (now - w.fireTime + 1);
                int ticks = (int) Math.ceil((d + 60 - r) / w.speed) + 6;
                if (ticks > H) H = ticks;
            }
            if (jn > 0) {
                double pw = Math.min(3.0, eEnergy);
                int ticks = jn + (int) Math.ceil((eDist + 90) / (20 - 3 * pw)) + 2;
                if (ticks > H) H = ticks;
            }
            H = Math.min(H, HMAX);
            double bestScore = Double.MAX_VALUE;
            for (int p = 0; p < PLANS.length; p++) {
                int[] pl = PLANS[p];
                simulate(pl[0], pl[1], pl[2], H, useK);
                double sc = evaluate(act, H, jn);
                double dEnd = Math.hypot(px[H] - (useK ? kx[H] : eX), py[H] - (useK ? ky[H] : eY));
                sc += 0.02 * Math.abs(dEnd - DESIRED_DIST) / DESIRED_DIST;
                double wm = minWallDist(H);
                if (wm < 70) sc += 0.05 * (70 - wm) / 70;
                if (pl[0] == 0) sc += 0.015;
                if (pl[0] != orbitDir && pl[0] != 0) sc += 0.01;
                if (sc < bestScore) { bestScore = sc; chosen = pl; }
            }
            if (chosen != null && DEBUG) {
                simulate(chosen[0], chosen[1], chosen[2], H, useK);
                lastBestDanger = evaluate(act, H, jn);
                for (EWave w : act) { if (w.firstDanger < 0) w.firstDanger = lastBestDanger; w.lastDanger = lastBestDanger; }
            }
        }
        int dir = orbitDir;
        if (chosen != null) dir = chosen[1] > 0 ? chosen[0] : chosen[2];
        else chosen = new int[]{orbitDir, 999, orbitDir};
        applyMove(dir);
        if (dir != 0) orbitDir = dir;
        // trajetoria do plano escolhido (para o canhao): horizonte longo
        simulate(chosen[0], chosen[1], chosen[2], HMAX, useK);
        simH = HMAX; simHasK = useK;
    }

    double minWallDist(int H) {
        double m = 1e9;
        for (int j = 1; j <= H; j += 2) {
            double d = Math.min(Math.min(px[j], bfW - px[j]), Math.min(py[j], bfH - py[j]));
            if (d < m) m = d;
        }
        return m;
    }

    /** aplica um passo de movimento de verdade. */
    void applyMove(int dir) {
        double h = myHeading;
        double distRem, turn = 0;
        if (dir == 0) {
            distRem = 0;
        } else {
            double ang = orbitAngle(myX, myY, dir, eX, eY);
            double rel = Utils.normalRelativeAngle(ang - h);
            double mv = 1;
            if (Math.abs(rel) > Math.PI / 2) { rel = Utils.normalRelativeAngle(rel + Math.PI); mv = -1; }
            turn = rel; distRem = mv * 1e4;
        }
        setTurnRightRadians(turn);
        setAhead(distRem);
    }

    void simulate(int d1, int s, int d2, int H, boolean withK) {
        double x = myX, y = myY, h = myHeading, v = myVel;
        px[0] = x; py[0] = y; ph[0] = h; pv[0] = v;
        double[] k = kTmp;
        boolean kMoves = withK && eEnergy > 0.001;
        if (withK) { k[0] = eX; k[1] = eY; k[2] = eHeading; k[3] = eVel; k[4] = 0; k[5] = kDir; kx[0] = eX; ky[0] = eY; }
        for (int j = 0; j < H; j++) {
            double cx = withK ? kx[j] : eX, cy = withK ? ky[j] : eY;
            int dir = j < s ? d1 : d2;
            double distRem, turn = 0;
            if (dir == 0) {
                distRem = 0;
            } else {
                double ang = orbitAngle(x, y, dir, cx, cy);
                double rel = Utils.normalRelativeAngle(ang - h);
                double mv = 1;
                if (Math.abs(rel) > Math.PI / 2) { rel = Utils.normalRelativeAngle(rel + Math.PI); mv = -1; }
                turn = rel; distRem = mv * 1e4;
            }
            if (withK) { if (kMoves) kStep(k, x, y, h, v); kx[j + 1] = k[0]; ky[j + 1] = k[1]; }
            double maxTurn = Math.toRadians(10 - 0.75 * Math.abs(v));
            if (turn > maxTurn) turn = maxTurn; else if (turn < -maxTurn) turn = -maxTurn;
            h += turn;
            v = newVelocity(v, distRem);
            x += v * Math.sin(h); y += v * Math.cos(h);
            if (x < HALF) x = HALF; else if (x > bfW - HALF) x = bfW - HALF;
            if (y < HALF) y = HALF; else if (y > bfH - HALF) y = bfH - HALF;
            px[j + 1] = x; py[j + 1] = y; ph[j + 1] = h; pv[j + 1] = v;
        }
    }

    double orbitAngle(double x, double y, int dir, double cx, double cy) {
        double dx = x - cx, dy = y - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double absB = Math.atan2(dx, dy);
        double tilt = clamp(-0.6, (dist - DESIRED_DIST) / DESIRED_DIST, 0.6);
        double ang = absB + dir * (Math.PI / 2 + tilt);
        return wallSmooth(x, y, ang, dir);
    }

    double wallSmooth(double x, double y, double ang, int dir) {
        int tries = 0;
        while (tries < 70 && !inField(x + Math.sin(ang) * STICK, y + Math.cos(ang) * STICK)) {
            ang += dir * 0.05; tries++;
        }
        return ang;
    }

    boolean inField(double x, double y) {
        return x >= WALL_SAFE && x <= bfW - WALL_SAFE && y >= WALL_SAFE && y <= bfH - WALL_SAFE;
    }

    /** replica a fisica de aceleracao do Robocode. distRem: >0 frente, <0 re, 0 parar. */
    static double newVelocity(double v, double distRem) {
        if (distRem < 0) return -newVelocity(-v, -distRem);
        double goal;
        if (distRem >= 1e3) goal = MAX_VEL; else goal = Math.min(maxVelFor(distRem), MAX_VEL);
        if (v >= 0) return Math.max(v - 2.0, Math.min(goal, v + 1.0));
        return Math.max(v - 1.0, Math.min(goal, v + maxDecel(-v)));
    }

    static double maxDecel(double speed) {
        double decelTime = speed / 2.0;
        double accelTime = 1 - decelTime;
        return Math.min(1, decelTime) * 2.0 + Math.max(0, accelTime) * 1.0;
    }

    static double maxVelFor(double distance) {
        double decelTime = Math.max(1, Math.ceil((Math.sqrt((4 * 2 / 2.0) * distance + 1) - 1) / 2));
        double decelDist = (decelTime / 2.0) * (decelTime - 1) * 2.0;
        return ((decelTime - 1) * 2.0) + ((distance - decelDist) / decelTime);
    }

    // ===================== avaliacao de caminhos =====================
    double evaluate(List<EWave> act, int H, int jn) {
        double total = 0;
        for (EWave w : act) total += waveDanger(w, H, true, 1.0);
        if (jn > 0 && simHasKNow()) {
            if (buildVirtualWave(jn, H)) {
                double wv = jn <= 6 ? 1.0 : 0.7;
                total += waveDanger(vw, H, false, wv);
            }
        }
        return total;
    }

    boolean simHasKNow() { return true; }

    /** onda virtual do proximo tiro do Kader, segundo o caminho simulado atual. */
    boolean buildVirtualWave(int jn, int H) {
        if (jn - 1 > H) return false;
        int a = jn - 2;
        double ax, ay, ah, av, kax, kay;
        if (a >= 0) {
            ax = px[a]; ay = py[a]; ah = ph[a]; av = pv[a]; kax = kx[a]; kay = ky[a];
        } else {
            if (!hasMe(now - 1) || !hasEn(now - 1)) return false;
            int i = (int) ((now - 1) & HM);
            ax = hMX[i]; ay = hMY[i]; ah = hMH[i]; av = hMV[i]; kax = hEX[i]; kay = hEY[i];
        }
        vw.modelAngle = modelAim(ax, ay, ah, av, kax, kay);
        vw.sx = kx[jn - 1]; vw.sy = ky[jn - 1];
        vw.power = Math.min(3.0, eEnergy);
        vw.speed = 20 - 3 * vw.power;
        vw.fireTime = now + jn;
        vw.ref = vw.modelAngle;
        vw.mea = Math.asin(MAX_VEL / vw.speed);
        vw.dir = 1; vw.seg = 0;
        return true;
    }

    double waveDanger(EWave w, int H, boolean learned, double weight) {
        double offMin = 1e9, offMax = -1e9;
        boolean touched = false;
        for (int j = 0; j < H; j++) {
            long t = now + 1 + j;
            double rNew = w.speed * (t - w.fireTime + 1);
            if (rNew <= 0) continue;
            double rOld = rNew - w.speed;
            double cx = px[j], cy = py[j];
            double nx = Math.max(cx - HALF, Math.min(w.sx, cx + HALF));
            double ny = Math.max(cy - HALF, Math.min(w.sy, cy + HALF));
            double dMin = Math.hypot(nx - w.sx, ny - w.sy);
            double dMax = Math.hypot(Math.abs(cx - w.sx) + HALF, Math.abs(cy - w.sy) + HALF);
            if (rNew >= dMin && rOld <= dMax) {
                touched = true;
                for (int c = 0; c < 4; c++) {
                    double qx = cx + ((c & 1) == 0 ? -HALF : HALF);
                    double qy = cy + ((c & 2) == 0 ? -HALF : HALF);
                    double off = Utils.normalRelativeAngle(Math.atan2(qx - w.sx, qy - w.sy) - w.ref);
                    if (off < offMin) offMin = off;
                    if (off > offMax) offMax = off;
                }
            } else if (touched && rOld > dMax) break;
        }
        if (!touched) return 0;
        double danger = 0;
        if (!Double.isNaN(w.modelAngle) && modelTrust > 0) {
            double mo = Utils.normalRelativeAngle(w.modelAngle - w.ref);
            double gap = 0;
            if (mo < offMin) gap = offMin - mo; else if (mo > offMax) gap = mo - offMax;
            double md = gap <= 0 ? 1.5 : Math.exp(-(gap / 0.045) * (gap / 0.045));
            danger += 10.0 * md;
        }
        if (learned) {
            double g1 = clamp(-1, offMin / w.mea * w.dir, 1), g2 = clamp(-1, offMax / w.mea * w.dir, 1);
            double lo = Math.min(g1, g2), hi = Math.max(g1, g2);
            danger += (modelTrust > 0 ? 0.5 : 3.0) * learnedDanger(w, lo, hi);
        }
        return danger * weight;
    }

    // ===================== canhao =====================
    void doGun(boolean fresh) {
        updateGunWaves();
        if (!fresh && now - lastFreshTime > 2) return;
        // 1) dispara com a mira calculada no tick anterior
        if (pending != null && getGunHeat() == 0) {
            double err = Math.abs(Utils.normalRelativeAngle(gunHeading - pending.aim));
            double tol = Math.max(0.01, Math.atan2(HALF, Math.max(eDist, 60)) * 0.45);
            if (err < tol && myEnergy > pending.power + 0.01) {
                Bullet b = setFireBullet(pending.power);
                if (b != null) {
                    GWave gw = new GWave();
                    gw.sx = myX; gw.sy = myY; gw.fireTime = now + 1;
                    gw.speed = 20 - 3 * pending.power; gw.ref = pending.ref; gw.mea = pending.mea;
                    gw.dir = pending.dir; gw.feat = pending.feat; gw.aimK = pending.aimK; gw.aimN = pending.aimN;
                    gWaves.add(gw);
                    sOurShots++; roundShots++;
                }
            }
        }
        // 2) calcula a mira para o proximo tick
        double mx = px[1], my = py[1];
        double ex1 = simHasK ? kx[1] : eX + eVel * Math.sin(eHeading);
        double ey1 = simHasK ? ky[1] : eY + eVel * Math.cos(eHeading);
        double ref = Math.atan2(ex1 - mx, ey1 - my);
        double dist = Math.hypot(ex1 - mx, ey1 - my);
        double power = choosePower(dist);
        double speed = 20 - 3 * power;
        double mea = Math.asin(MAX_VEL / speed);
        double[] f = features(dist);
        double gf = 0;
        boolean still = Math.abs(eVel) < 0.01 && Math.abs(ePrevVel) < 0.01;
        if (!still && getGunHeat() < 0.35) gf = queryGF(f, Math.max(0.03, Math.atan2(HALF, dist) / mea * 0.7));
        double aimN = ref + eDir * gf * mea;
        double aimK = simHasK ? interceptAim(mx, my, speed) : Double.NaN;
        boolean useK = !Double.isNaN(aimK) && scoreK >= scoreN - 0.1;
        Pending p = new Pending();
        p.aim = useK ? aimK : aimN; p.aimK = aimK; p.aimN = aimN;
        p.power = power; p.ref = ref; p.mea = mea; p.dir = eDir; p.feat = f;
        pending = p;
        setTurnGunRightRadians(Utils.normalRelativeAngle(p.aim - gunHeading));
    }

    /** angulo para interceptar o oponente simulado: primeira vez que a frente da bala alcanca a posicao prevista. */
    double interceptAim(double sx, double sy, double bulletSpeed) {
        for (int t = 0; t + 1 <= simH; t++) {
            int j = 1 + t;
            double r = bulletSpeed * (t + 1);
            double dx = kx[j] - sx, dy = ky[j] - sy;
            if (r >= Math.sqrt(dx * dx + dy * dy)) return Math.atan2(dx, dy);
        }
        return Double.NaN;
    }

    double choosePower(double dist) {
        double p;
        if (dist < 200) p = POWER_NEAR; else if (dist < 350) p = POWER_MID; else p = POWER_FAR;
        double kill = eEnergy <= 4 ? eEnergy / 4 : (eEnergy + 2) / 6;
        if (p > kill + 0.05) p = Math.max(0.1, kill + 0.05);
        p = Math.min(p, myEnergy - 0.05);
        return Math.max(0.1, Math.min(3.0, p));
    }

    double[] features(double dist) {
        double[] f = new double[NF];
        f[0] = Math.min(1, dist / 800.0);
        f[1] = Math.min(1, Math.abs(eLat) / 8.0);
        f[2] = (eAdv + 8) / 16.0;
        double acc = Math.abs(eVel) - Math.abs(ePrevVel);
        f[3] = (clamp(-2, acc, 1) + 2) / 3.0;
        double ang = eAbs + eDir * Math.PI / 2;
        double room = roomAhead(eX, eY, ang);
        f[4] = Math.min(1, room / 400.0);
        f[5] = Math.min(1, (now - eDirChange) / 40.0);
        double myLat = myVel * Math.sin(myHeading - (eAbs + Math.PI));
        f[6] = (clamp(-8, myLat * eDir, 8) + 8) / 16.0;
        return f;
    }

    double roomAhead(double x, double y, double ang) {
        double sx = Math.sin(ang), cy = Math.cos(ang);
        double tx = sx > 1e-9 ? (bfW - HALF - x) / sx : (sx < -1e-9 ? (HALF - x) / sx : 1e9);
        double ty = cy > 1e-9 ? (bfH - HALF - y) / cy : (cy < -1e-9 ? (HALF - y) / cy : 1e9);
        return Math.max(0, Math.min(tx, ty));
    }

    double queryGF(double[] q, double sigma) {
        int n = gSamples.size();
        if (n < 8) return 0;
        int K = Math.min(KNN, n);
        double[] bd = new double[K];
        int[] bi = new int[K];
        for (int k = 0; k < K; k++) { bd[k] = Double.MAX_VALUE; bi[k] = -1; }
        for (int i = 0; i < n; i++) {
            double[] s = gSamples.get(i);
            double d = 0;
            for (int f = 0; f < NF; f++) { double df = s[f] - q[f]; d += FW[f] * df * df; }
            if (d < bd[K - 1]) {
                int k = K - 1;
                while (k > 0 && bd[k - 1] > d) { bd[k] = bd[k - 1]; bi[k] = bi[k - 1]; k--; }
                bd[k] = d; bi[k] = i;
            }
        }
        double[] dens = new double[GF_BINS];
        double h2 = Math.max(1e-6, bd[K - 1]) * 0.5 + 0.02;
        for (int k = 0; k < K; k++) {
            if (bi[k] < 0) continue;
            double w = 1.0 / (1.0 + bd[k] / h2);
            double gf = gSamples.get(bi[k])[NF];
            for (int b = 0; b < GF_BINS; b++) {
                double g = -1 + 2.0 * b / (GF_BINS - 1);
                double z = (g - gf) / sigma;
                dens[b] += w * Math.exp(-0.5 * z * z);
            }
        }
        int best = GF_BINS / 2; double bv = -1;
        for (int b = 0; b < GF_BINS; b++) {
            if (dens[b] > bv + 1e-12) { bv = dens[b]; best = b; }
        }
        return -1 + 2.0 * best / (GF_BINS - 1);
    }

    void updateGunWaves() {
        for (Iterator<GWave> it = gWaves.iterator(); it.hasNext();) {
            GWave w = it.next();
            if (now < w.fireTime) continue;
            double r = w.speed * (now - w.fireTime + 1);
            double cx, cy;
            if (hasEn(now - 1)) { int i = (int) ((now - 1) & HM); cx = hEX[i]; cy = hEY[i]; } else { cx = eX; cy = eY; }
            double d = Math.hypot(cx - w.sx, cy - w.sy);
            if (r >= d) {
                double ang = Math.atan2(cx - w.sx, cy - w.sy);
                double off = Utils.normalRelativeAngle(ang - w.ref);
                double gf = clamp(-1, off / w.mea * w.dir, 1);
                double[] s = new double[NF + 1];
                System.arraycopy(w.feat, 0, s, 0, NF);
                s[NF] = gf;
                gSamples.add(s);
                if (gSamples.size() > 6000) gSamples.remove(0);
                // desempenho virtual das duas miras
                double hw = Math.atan2(16, Math.max(d, 30));
                boolean hitN = Math.abs(Utils.normalRelativeAngle(ang - w.aimN)) < hw;
                scoreN = 0.85 * scoreN + 0.15 * (hitN ? 1 : 0);
                if (hitN) vHitsN++;
                if (!Double.isNaN(w.aimK)) {
                    boolean hitK = Math.abs(Utils.normalRelativeAngle(ang - w.aimK)) < hw;
                    scoreK = 0.85 * scoreK + 0.15 * (hitK ? 1 : 0);
                    vShotsK++; if (hitK) vHitsK++;
                }
                it.remove();
            } else if (now - w.fireTime > 200) it.remove();
        }
    }

    // ===================== radar =====================
    void doRadar(boolean fresh) {
        if (!hasEnemy || now - lastFreshTime > 2) { setTurnRadarRightRadians(Double.POSITIVE_INFINITY); return; }
        double off = Utils.normalRelativeAngle(eAbs - radarHeading);
        double extra = Math.min(0.6, Math.atan(40.0 / Math.max(eDist, 40)) + 0.04);
        double turn = off + (off >= 0 ? extra : -extra);
        setTurnRadarRightRadians(turn);
    }

    // ===================== utilitarios =====================
    static double clamp(double lo, double v, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
