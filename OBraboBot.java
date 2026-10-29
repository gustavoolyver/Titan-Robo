package obrabo;

import robocode.*;
import robocode.util.Utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.*;

/**
 * ============================================================================
 * OBraboBot - Robô Adaptativo de Batalha de Elite (Edição Grandmaster v4)
 * ============================================================================
 * 
 * Implementação do Caso de Uso UC-001 integrada a todas as 7 técnicas de ponta:
 * 
 * 1. Arquitetura 100% Assíncrona (AdvancedRobot sem travas de execução).
 * 2. Detecção Dual-Mode: Radar Lock Infinitesimal em 1v1 vs Varredura 360° em Melee.
 * 3. Q-Learning Tabular Adaptativo (144 estados x 5 ações com macro-ações de 16 ticks).
 * 4. Segmented GuessFactor Targeting (S-GFT) multidimensional (3 dist x 3 vel x 2 TSDC x 31 bins).
 * 5. Sistema Quádruplo de Miras Virtuais com Seleção Dinâmica em Tempo Real.
 * 6. Rastreamento Preciso de Gun Heat Inimigo (Zero Latency Gun Tracker a 0.1/tick).
 * 7. Disparos Econômicos de Finalização (Kill Shots milimétricos sem desperdício de energia).
 * 8. Repulsão Gravitacional de Cantos (Anti-Corner Trap) integrada ao Wall Smoothing.
 * 9. Achatador de Perfil de Movimento (Profile Flattener com micro-variações de velocidade).
 * 10. Movimentação de Risco Mínimo (Minimum Risk Movement - MRM) para batalhas em grupo (Melee).
 * 11. Evasão por Densidade de Risco (Wave Surfing).
 * 12. Persistência 100% Automática em Arquivo Local Único (sem dependência de servidores).
 * 13. Telemetria Gráfica em Tempo Real na Janela de Batalha (onPaint).
 * 
 * NOTA: Basta compilar este arquivo UMA ÚNICA VEZ no Robocode Editor!
 * 
 * @author Equipe de Engenharia & Caso de Uso UC-001
 * @version 4.0 (Edição Grandmaster)
 */
public class OBraboBot extends AdvancedRobot {

    // ========================================================================
    // Hiperparâmetros de Aprendizado por Reforço (Q-Learning)
    // ========================================================================
    private static final double ALPHA = 0.18;          // Taxa de aprendizagem
    private static final double GAMMA = 0.88;          // Fator de desconto temporal
    private static final double INITIAL_EPSILON = 0.35;// Taxa de exploração inicial
    private static final double MIN_EPSILON = 0.04;    // Exploração mínima garantida
    private static final double EPSILON_DECAY = 0.985; // Decaimento gradual por round
    private static final int DECISION_INTERVAL = 16;   // Ticks entre decisões de macro-ação

    // ========================================================================
    // Dimensões do Espaço de Estados (Total: 4 * 3 * 3 * 2 * 2 = 144 estados)
    // ========================================================================
    private static final int TOTAL_STATES = 144;

    // ========================================================================
    // Catálogo de Ações Táticas
    // ========================================================================
    public static final int ACTION_ORBIT_CW      = 0; // Órbita Perpendicular Horária
    public static final int ACTION_ORBIT_CCW     = 1; // Órbita Perpendicular Anti-Horária
    public static final int ACTION_CHARGE        = 2; // Carga Ofensiva / Pressão / Ramming
    public static final int ACTION_RETREAT       = 3; // Recuo Evasivo para Espaço Aberto
    public static final int ACTION_STOP_AND_GO   = 4; // Hesitação / Quebra de Padrão Preditivo
    public static final int NUM_ACTIONS          = 5;

    // ========================================================================
    // Sistema Quádruplo de Miras Virtuais (Virtual Guns)
    // ========================================================================
    private static final int GUN_HEAD_ON      = 0;
    private static final int GUN_LINEAR       = 1;
    private static final int GUN_CIRCULAR     = 2;
    private static final int GUN_GUESS_FACTOR = 3;
    private static final int NUM_GUNS         = 4;

    // Dimensões do Segmented GuessFactor Targeting (S-GFT)
    private static final int BINS_GF      = 31;
    private static final int SLICES_DIST  = 3; // 0: Curta, 1: Média, 2: Longa
    private static final int SLICES_VEL   = 3; // 0: Lenta, 1: Média, 2: Alta
    private static final int SLICES_TSDC  = 2; // 0: Mudança recente, 1: Movimento contínuo

    // ========================================================================
    // Estruturas Estáticas (Persistentes entre Rounds na mesma JVM)
    // ========================================================================
    private static double[][] qTable = new double[TOTAL_STATES][NUM_ACTIONS];
    private static int[] gunScores = new int[NUM_GUNS];
    private static double[][][][] segmentedGFT = new double[SLICES_DIST][SLICES_VEL][SLICES_TSDC][BINS_GF];
    private static double[] surfDangerProfile = new double[BINS_GF];

    private static double epsilon = INITIAL_EPSILON;
    private static int totalBattles = 0;
    private static int totalRounds = 0;
    private static int totalWins = 0;
    private static int consecutiveLosses = 0;
    private static boolean memoryLoaded = false;

    // ========================================================================
    // Estado Dinâmico da Partida (Por Round)
    // ========================================================================
    private EnemyTracker primaryEnemy = new EnemyTracker();
    private Map<String, EnemyRecord> meleeEnemies = new HashMap<String, EnemyRecord>();

    private List<VirtualBullet> virtualBullets = new ArrayList<VirtualBullet>();
    private List<EnemyWave> enemyWaves = new ArrayList<EnemyWave>();
    private List<PlayerWave> playerWaves = new ArrayList<PlayerWave>();

    private int currentState = -1;
    private int currentAction = -1;
    private long lastDecisionTick = -100;
    private double roundAccumulatedReward = 0.0;
    private double stepRewardAccumulator = 0.0;

    private double prevMyEnergy = 100.0;
    private double prevEnemyEnergy = 100.0;
    private double enemyGunHeat = 0.0; // Rastreamento de Gun Heat do Inimigo
    private int moveDirection = 1;
    private boolean bulletWaveDetected = false;
    private long lastDirectionChangeTick = 0;
    private long lastDodgeTime = -100;
    private int dodgeStreak = 0;

    // ========================================================================
    // Ciclo Principal de Execução do Robô
    // ========================================================================
    @Override
    public void run() {
        if (!memoryLoaded) {
            loadMemory();
            memoryLoaded = true;
        }

        totalRounds++;

        // Desacoplamento completo dos motores
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        // Estilo visual de campeonato
        // Estilo 'Instinto Superior' (Ultra Instinct): Preto Furtivo + Aura Prateada e Ciano Eletrico
        setColors(new Color(10, 10, 15),      // Chassi Preto Profundo
                  new Color(240, 240, 250),   // Canhao Branco Prateado (Instinto Superior)
                  new Color(56, 189, 248),    // Radar Azul Ciano Eletrico
                  new Color(255, 255, 255),   // Tiros Brancos Puros
                  new Color(125, 211, 252));  // Feixe de Radar Neon

        // Varredura de radar inicial contínua
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);

        // Loop de ticks - 100% assíncrono
        while (true) {
            boolean isMelee = (getOthers() > 1);

            // Em Melee, o radar varre sem parar; em 1v1, retoma caso pare
            if (isMelee || getRadarTurnRemaining() == 0.0) {
                setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            }

            // Profile Flattener: micro-variação de velocidade para quebrar miras inimigas
            if (!isMelee) {
                double targetVel = (getTime() % 13 < 4) ? 6.2 : ((getTime() % 19 < 5) ? 7.2 : 8.0);
                setMaxVelocity(targetVel);
            } else {
                setMaxVelocity(8.0);
            }

            execute();
        }
    }

    // ========================================================================
    // Sensor de Escaneamento (Coração Tático do Robô)
    // ========================================================================
    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        long currentTick = getTime();
        boolean isMelee = (getOthers() > 1);

        // 1. Atualizar registro do robô escaneado
        if (isMelee) {
            EnemyRecord rec = meleeEnemies.get(e.getName());
            if (rec == null) {
                rec = new EnemyRecord();
                meleeEnemies.put(e.getName(), rec);
            }
            rec.update(e, this);

            // Seleção de Alvo Fraco em Melee: foca no robô de menor HP ou mais próximo
            selectMeleeTarget(e);
        } else {
            primaryEnemy.update(e, this);
        }

        // 2. Controle de Radar (1v1: Radar Lock Infinitesimal; Melee: Varredura Contínua)
        if (!isMelee) {
            double radarTurn = Utils.normalRelativeAngle(primaryEnemy.absBearing - getRadarHeadingRadians());
            setTurnRadarRightRadians(radarTurn * 2.0);
        }

        // 3. Rastreamento Preciso de Gun Heat e Ondas de Balas
        enemyGunHeat = Math.max(0.0, enemyGunHeat - 0.1);
        double energyDelta = prevEnemyEnergy - primaryEnemy.energy;
        bulletWaveDetected = (energyDelta >= 0.09 && energyDelta <= 3.01);

        if (bulletWaveDetected) {
            enemyGunHeat = 1.0 + (energyDelta / 5.0); // Canhão inimigo esquenta

            EnemyWave w = new EnemyWave();
            w.fireTime = currentTick - 1;
            w.bulletVelocity = 20.0 - 3.0 * energyDelta;
            w.fireLocation = new Point2D.Double(primaryEnemy.x, primaryEnemy.y);
            w.directAngle = primaryEnemy.absBearing + Math.PI;
            w.direction = (primaryEnemy.lateralVelocity >= 0) ? 1 : -1;
            enemyWaves.add(w);
        }
        prevEnemyEnergy = primaryEnemy.energy;

        // 4. Atualizar Ondas Próprias (S-GFT) e Tiros Virtuais
        updatePlayerWaves();
        updateVirtualBullets();

        // 5. Decisão de Mira e Disparo Adaptativo com Kill Shot
        executeAdaptiveFire();

        // 6. Decisão de Movimentação (Melee: Minimum Risk Movement; 1v1: Q-Learning + Wave Surfing)
        if (isMelee) {
            executeMinimumRiskMovement();
        } else {
            int observedState = encodeState(primaryEnemy.distance, primaryEnemy.lateralVelocity, 
                                            getEnergy() - primaryEnemy.energy, getWallProximity(), bulletWaveDetected);

            if (currentTick - lastDecisionTick >= DECISION_INTERVAL || currentState == -1) {
                if (currentState != -1 && currentAction != -1) {
                    learn(currentState, currentAction, stepRewardAccumulator, observedState, false);
                    stepRewardAccumulator = 0.0;
                }
                currentState = observedState;
                currentAction = selectAction(currentState);
                lastDecisionTick = currentTick;
            }

            executeTacticalMovement(currentAction);
        }

        // 7. Atualização de Dano e Recompensas
        double damageTaken = prevMyEnergy - getEnergy();
        if (damageTaken > 0) {
            stepRewardAccumulator -= damageTaken * 0.25;
            roundAccumulatedReward -= damageTaken * 0.25;
        }
        prevMyEnergy = getEnergy();
    }

    private void selectMeleeTarget(ScannedRobotEvent e) {
        // Se ainda não temos alvo principal ou o novo alvo tem menos HP e está perto
        if (primaryEnemy.name.isEmpty() || e.getName().equals(primaryEnemy.name)) {
            primaryEnemy.update(e, this);
        } else {
            EnemyRecord current = meleeEnemies.get(primaryEnemy.name);
            if (current == null || (e.getEnergy() < current.energy - 10.0 && e.getDistance() < 550.0)) {
                primaryEnemy.update(e, this);
            }
        }
    }

    // ========================================================================
    // Movimentação Tática com Anti-Corner Trap e Viés de Wave Surfing (1v1)
    // ========================================================================
    private void executeTacticalMovement(int action) {
        double desiredAngle = primaryEnemy.absBearing;

        // Modo de Finalização (Lethal Ramming): se o inimigo tem pouca vida, atropela
        if (primaryEnemy.energy < 14.0 && getEnergy() > 28.0) {
            desiredAngle = primaryEnemy.absBearing;
            double safeAngle = wallSmoothing(getX(), getY(), desiredAngle, 1);
            setBackAsForward(safeAngle, 180.0);
            return;
        }

        switch (action) {
            case ACTION_ORBIT_CW:
                desiredAngle = primaryEnemy.absBearing + (Math.PI / 2.0);
                moveDirection = 1;
                break;

            case ACTION_ORBIT_CCW:
                desiredAngle = primaryEnemy.absBearing - (Math.PI / 2.0);
                moveDirection = 1;
                break;

            case ACTION_CHARGE:
                desiredAngle = primaryEnemy.absBearing + (moveDirection > 0 ? (Math.PI / 4.0) : -(Math.PI / 4.0));
                break;

            case ACTION_RETREAT:
                desiredAngle = primaryEnemy.absBearing + Math.PI + (moveDirection > 0 ? 0.35 : -0.35);
                break;

            case ACTION_STOP_AND_GO:
                // Só hesita se o inimigo puder atirar (gunHeat baixo); senão, move-se normalmente
                if (enemyGunHeat < 0.2 && (getTime() % 14) < 6) {
                    setAhead(0);
                    return;
                } else {
                    desiredAngle = primaryEnemy.absBearing + (Math.PI / 2.0);
                    if (getTime() % 28 == 0) {
                        moveDirection = -moveDirection;
                        lastDirectionChangeTick = getTime();
                    }
                }
                break;

            default:
                desiredAngle = primaryEnemy.absBearing + (Math.PI / 2.0);
                break;
        }

        // Repulsão Gravitacional de Cantos (Anti-Corner Trap)
        desiredAngle = applyAntiCornerRepulsion(getX(), getY(), desiredAngle);

        // Viés de Wave Surfing: desvia na direção de menor densidade de risco
        if (!enemyWaves.isEmpty()) {
            EnemyWave closestWave = enemyWaves.get(0);
            double distToWave = closestWave.fireLocation.distance(getX(), getY()) - (getTime() - closestWave.fireTime) * closestWave.bulletVelocity;
            if (distToWave < 120.0 && distToWave > -30.0) {
                int dangerCW = 0;
                int dangerCCW = 0;
                for (int i = 0; i < BINS_GF / 2; i++) dangerCCW += (int) surfDangerProfile[i];
                for (int i = BINS_GF / 2; i < BINS_GF; i++) dangerCW += (int) surfDangerProfile[i];
                if (dangerCW > dangerCCW) {
                    desiredAngle = primaryEnemy.absBearing - (Math.PI / 2.0);
                } else {
                    desiredAngle = primaryEnemy.absBearing + (Math.PI / 2.0);
                }
            }
        }

        // Wall Smoothing Iterativo
        double safeAngle = wallSmoothing(getX(), getY(), desiredAngle, moveDirection);
        setBackAsForward(safeAngle, 140.0);
    }

    /**
     * Anti-Corner Trap: repele o robô de qualquer um dos 4 cantos para evitar encurralamento.
     */
    private double applyAntiCornerRepulsion(double x, double y, double heading) {
        double w = getBattleFieldWidth();
        double h = getBattleFieldHeight();
        double cornerThreshold = 130.0;

        boolean nearCorner = ((x < cornerThreshold || x > w - cornerThreshold) &&
                              (y < cornerThreshold || y > h - cornerThreshold));

        if (nearCorner) {
            // Vetor apontando para o centro da arena
            double centerX = w / 2.0;
            double centerY = h / 2.0;
            double angleToCenter = Math.atan2(centerX - x, centerY - y);
            return angleToCenter;
        }
        return heading;
    }

    /**
     * Minimum Risk Movement (MRM) para Batalhas em Grupo (Melee):
     * Avalia 16 pontos cardeais em volta do robô e escolhe o de menor risco acumulado.
     */
    private void executeMinimumRiskMovement() {
        double bestRisk = Double.POSITIVE_INFINITY;
        double bestAngle = getHeadingRadians();
        double evalDist = 130.0;

        for (int i = 0; i < 16; i++) {
            double ang = (2.0 * Math.PI / 16.0) * i;
            double candX = getX() + Math.sin(ang) * evalDist;
            double candY = getY() + Math.cos(ang) * evalDist;

            // Ponto fora dos limites é risco infinito
            if (candX < 40 || candX > getBattleFieldWidth() - 40 ||
                candY < 40 || candY > getBattleFieldHeight() - 40) {
                continue;
            }

            // 1. Risco de paredes e cantos
            double wallDist = Math.min(Math.min(candX, getBattleFieldWidth() - candX),
                                       Math.min(candY, getBattleFieldHeight() - candY));
            double risk = (5000.0 / (wallDist * wallDist));

            // 2. Risco de proximidade com outros robôs (repulsão por energia)
            for (EnemyRecord enemyRec : meleeEnemies.values()) {
                double d = Point2D.distance(candX, candY, enemyRec.x, enemyRec.y);
                risk += (enemyRec.energy * 10000.0) / (d * d);
            }

            // 3. Preferência por manter o curso (menor ângulo de rotação)
            double turnDiff = Math.abs(Utils.normalRelativeAngle(ang - getHeadingRadians()));
            risk += turnDiff * 0.4;

            if (risk < bestRisk) {
                bestRisk = risk;
                bestAngle = ang;
            }
        }

        double safeAngle = wallSmoothing(getX(), getY(), bestAngle, 1);
        setBackAsForward(safeAngle, 140.0);
    }

    private double wallSmoothing(double x, double y, double targetAngle, int dir) {
        double angle = targetAngle;
        double wallMargin = 40.0;
        double width = getBattleFieldWidth();
        double height = getBattleFieldHeight();

        for (int i = 0; i < 45; i++) {
            double testX = x + Math.sin(angle) * dir * 110.0;
            double testY = y + Math.cos(angle) * dir * 110.0;

            if (testX >= wallMargin && testX <= width - wallMargin &&
                testY >= wallMargin && testY <= height - wallMargin) {
                return angle;
            }
            angle += dir * 0.09;
        }
        return angle;
    }

    private void setBackAsForward(double targetAngle, double distance) {
        double angle = Utils.normalRelativeAngle(targetAngle - getHeadingRadians());
        if (Math.abs(angle) > (Math.PI / 2.0)) {
            if (angle < 0) {
                setTurnRightRadians(Math.PI + angle);
            } else {
                setTurnLeftRadians(Math.PI - angle);
            }
            setAhead(-distance);
        } else {
            if (angle < 0) {
                setTurnLeftRadians(-angle);
            } else {
                setTurnRightRadians(angle);
            }
            setAhead(distance);
        }
    }

    // ========================================================================
    // Sistema Quádruplo de Miras Virtuais com Segmented GFT e Kill Shots
    // ========================================================================
    private void executeAdaptiveFire() {
        if (primaryEnemy.name.isEmpty()) return;

        double bulletPower = calculateBulletPower();
        double bulletSpeed = 20.0 - 3.0 * bulletPower;

        double angHeadOn = primaryEnemy.absBearing;
        double angLinear = predictLinearTargeting(bulletSpeed);
        double angCircular = predictCircularTargeting(bulletSpeed);
        double angGuessFactor = predictSegmentedGuessFactor(bulletSpeed);

        // Seleciona a mira de maior taxa de acerto comprovada
        int bestGun = GUN_GUESS_FACTOR;
        int maxScore = gunScores[GUN_GUESS_FACTOR];

        for (int g = 0; g < NUM_GUNS; g++) {
            if (gunScores[g] > maxScore) {
                maxScore = gunScores[g];
                bestGun = g;
            }
        }

        double chosenAngle = angGuessFactor;
        if (bestGun == GUN_CIRCULAR) chosenAngle = angCircular;
        else if (bestGun == GUN_LINEAR) chosenAngle = angLinear;
        else if (bestGun == GUN_HEAD_ON) chosenAngle = angHeadOn;

        double gunTurn = Utils.normalRelativeAngle(chosenAngle - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        if (getGunHeat() == 0.0 && Math.abs(gunTurn) < Math.toRadians(3.5) && getEnergy() > bulletPower) {
            Bullet b = setFireBullet(bulletPower);
            if (b != null) {
                registerVirtualBullets(bulletPower, angHeadOn, angLinear, angCircular, angGuessFactor);
                registerPlayerWave(bulletPower);
            }
        }
    }

    /**
     * Kill Shot & Dynamic Bullet Power: calcula a potência exata para abater o alvo sem desperdiçar HP.
     */
    private double calculateBulletPower() {
        double power = 400.0 / primaryEnemy.distance;
        power = Math.max(1.0, Math.min(3.0, power));

        // Kill Shot de Precisão: se o inimigo tem pouca vida, atira com a energia estritamente necessária
        if (primaryEnemy.energy <= 4.0) {
            power = Math.min(power, (primaryEnemy.energy / 4.0) + 0.01);
        } else if (primaryEnemy.energy <= 16.0) {
            power = Math.min(power, ((primaryEnemy.energy + 2.0) / 6.0) + 0.01);
        }

        if (getEnergy() < 16.0) {
            power = Math.min(power, 1.2);
        }
        return Math.max(0.1, power);
    }

    private double predictLinearTargeting(double bulletSpeed) {
        double futureTime = primaryEnemy.distance / bulletSpeed;
        double futureX = primaryEnemy.x + Math.sin(primaryEnemy.headingRadians) * primaryEnemy.velocity * futureTime;
        double futureY = primaryEnemy.y + Math.cos(primaryEnemy.headingRadians) * primaryEnemy.velocity * futureTime;

        futureX = Math.max(18.0, Math.min(getBattleFieldWidth() - 18.0, futureX));
        futureY = Math.max(18.0, Math.min(getBattleFieldHeight() - 18.0, futureY));

        return Math.atan2(futureX - getX(), futureY - getY());
    }

    private double predictCircularTargeting(double bulletSpeed) {
        double futureX = primaryEnemy.x;
        double futureY = primaryEnemy.y;
        double futureHeading = primaryEnemy.headingRadians;
        double futureTime = 0;

        while ((++futureTime) * bulletSpeed < Point2D.distance(getX(), getY(), futureX, futureY) && futureTime < 60) {
            futureHeading += primaryEnemy.turnRate;
            futureX += Math.sin(futureHeading) * primaryEnemy.velocity;
            futureY += Math.cos(futureHeading) * primaryEnemy.velocity;

            if (futureX < 18.0 || futureX > getBattleFieldWidth() - 18.0 ||
                futureY < 18.0 || futureY > getBattleFieldHeight() - 18.0) {
                futureX = Math.max(18.0, Math.min(getBattleFieldWidth() - 18.0, futureX));
                futureY = Math.max(18.0, Math.min(getBattleFieldHeight() - 18.0, futureY));
                break;
            }
        }
        return Math.atan2(futureX - getX(), futureY - getY());
    }

    private double predictSegmentedGuessFactor(double bulletSpeed) {
        int dSlice = (primaryEnemy.distance < 280) ? 0 : (primaryEnemy.distance < 500 ? 1 : 2);
        int vSlice = (Math.abs(primaryEnemy.lateralVelocity) < 2.0) ? 0 : (Math.abs(primaryEnemy.lateralVelocity) < 5.0 ? 1 : 2);
        int tsdcSlice = (getTime() - lastDirectionChangeTick < 16) ? 0 : 1;

        double[] bins = segmentedGFT[dSlice][vSlice][tsdcSlice];

        int bestBin = (BINS_GF - 1) / 2;
        double maxCount = -1.0;
        for (int i = 0; i < BINS_GF; i++) {
            if (bins[i] > maxCount) {
                maxCount = bins[i];
                bestBin = i;
            }
        }

        double guessFactor = ((double) bestBin / (double) (BINS_GF - 1)) * 2.0 - 1.0;
        double maxEscapeAngle = Math.asin(8.0 / bulletSpeed);
        int lateralDir = (primaryEnemy.lateralVelocity >= 0) ? 1 : -1;

        return Utils.normalAbsoluteAngle(primaryEnemy.absBearing + guessFactor * maxEscapeAngle * lateralDir);
    }

    private void registerPlayerWave(double power) {
        PlayerWave pw = new PlayerWave();
        pw.fireLocation = new Point2D.Double(getX(), getY());
        pw.fireTime = getTime();
        pw.bulletVelocity = 20.0 - 3.0 * power;
        pw.directAngle = primaryEnemy.absBearing;
        pw.direction = (primaryEnemy.lateralVelocity >= 0) ? 1 : -1;
        pw.distSlice = (primaryEnemy.distance < 280) ? 0 : (primaryEnemy.distance < 500 ? 1 : 2);
        pw.velSlice = (Math.abs(primaryEnemy.lateralVelocity) < 2.0) ? 0 : (Math.abs(primaryEnemy.lateralVelocity) < 5.0 ? 1 : 2);
        pw.tsdcSlice = (getTime() - lastDirectionChangeTick < 16) ? 0 : 1;
        playerWaves.add(pw);
    }

    private void updatePlayerWaves() {
        Iterator<PlayerWave> it = playerWaves.iterator();
        while (it.hasNext()) {
            PlayerWave pw = it.next();
            double distTraveled = (getTime() - pw.fireTime) * pw.bulletVelocity;
            double currentEnemyDist = pw.fireLocation.distance(primaryEnemy.x, primaryEnemy.y);

            if (distTraveled >= currentEnemyDist) {
                double currentAbsAngle = Math.atan2(primaryEnemy.x - pw.fireLocation.x, primaryEnemy.y - pw.fireLocation.y);
                double offset = Utils.normalRelativeAngle(currentAbsAngle - pw.directAngle);
                double maxEscapeAngle = Math.asin(8.0 / pw.bulletVelocity);
                double guessFactor = Math.max(-1.0, Math.min(1.0, (offset / maxEscapeAngle) * pw.direction));

                int binIndex = (int) Math.round((guessFactor + 1.0) / 2.0 * (BINS_GF - 1));
                binIndex = Math.max(0, Math.min(BINS_GF - 1, binIndex));

                segmentedGFT[pw.distSlice][pw.velSlice][pw.tsdcSlice][binIndex] += 1.0;
                it.remove();
            }
        }
    }

    private void registerVirtualBullets(double power, double hOn, double lin, double circ, double gf) {
        double v = 20.0 - 3.0 * power;
        virtualBullets.add(new VirtualBullet(getX(), getY(), getTime(), v, hOn, GUN_HEAD_ON));
        virtualBullets.add(new VirtualBullet(getX(), getY(), getTime(), v, lin, GUN_LINEAR));
        virtualBullets.add(new VirtualBullet(getX(), getY(), getTime(), v, circ, GUN_CIRCULAR));
        virtualBullets.add(new VirtualBullet(getX(), getY(), getTime(), v, gf, GUN_GUESS_FACTOR));
    }

    private void updateVirtualBullets() {
        Iterator<VirtualBullet> it = virtualBullets.iterator();
        while (it.hasNext()) {
            VirtualBullet vb = it.next();
            double flightDist = (getTime() - vb.fireTime) * vb.velocity;
            double currentEnemyDist = Point2D.distance(vb.startX, vb.startY, primaryEnemy.x, primaryEnemy.y);

            if (flightDist >= currentEnemyDist) {
                double predX = vb.startX + Math.sin(vb.heading) * flightDist;
                double predY = vb.startY + Math.cos(vb.heading) * flightDist;

                if (Point2D.distance(predX, predY, primaryEnemy.x, primaryEnemy.y) <= 36.0) {
                    gunScores[vb.gunType]++;
                }
                it.remove();
            }
        }

        Iterator<EnemyWave> ewIt = enemyWaves.iterator();
        while (ewIt.hasNext()) {
            EnemyWave ew = ewIt.next();
            double flightDist = (getTime() - ew.fireTime) * ew.bulletVelocity;
            if (flightDist > ew.fireLocation.distance(getX(), getY()) + 35.0) {
                dodgeStreak++;
                lastDodgeTime = getTime();
                if (dodgeStreak >= 2 && dodgeStreak % 2 == 0) {
                    System.out.printf("[OBraboBot] ? (Esquiva de Instinto Superior #%d | 10,000 hours in this game 💀)
", dodgeStreak);
                }
                ewIt.remove();
            }
        }
    }

    // ========================================================================
    // Motor de Aprendizagem por Reforço (Q-Learning Tabular)
    // ========================================================================
    private int selectAction(int state) {
        if (Math.random() < epsilon) {
            return (int) (Math.random() * NUM_ACTIONS);
        }
        int best = 0;
        double maxQ = qTable[state][0];
        for (int a = 1; a < NUM_ACTIONS; a++) {
            if (qTable[state][a] > maxQ) {
                maxQ = qTable[state][a];
                best = a;
            }
        }
        return best;
    }

    private void learn(int s, int a, double r, int nextS, boolean done) {
        if (s < 0 || s >= TOTAL_STATES || a < 0 || a >= NUM_ACTIONS) return;

        double currentQ = qTable[s][a];
        double maxNextQ = 0.0;
        if (!done && nextS >= 0 && nextS < TOTAL_STATES) {
            maxNextQ = qTable[nextS][0];
            for (int i = 1; i < NUM_ACTIONS; i++) {
                if (qTable[nextS][i] > maxNextQ) {
                    maxNextQ = qTable[nextS][i];
                }
            }
        }

        double target = r + GAMMA * maxNextQ;
        qTable[s][a] = currentQ + ALPHA * (target - currentQ);
    }

    private int encodeState(double dist, double lateralVel, double energyDiff, double wallDist, boolean threat) {
        int dBucket = (dist < 220) ? 0 : (dist < 450 ? 1 : (dist < 700 ? 2 : 3));
        int sBucket = (Math.abs(lateralVel) < 1.8) ? 1 : (lateralVel > 1.8 ? 2 : 0);
        int eBucket = (energyDiff < -14.0) ? 0 : (energyDiff > 14.0 ? 2 : 1);
        int wBucket = (wallDist < 110.0) ? 1 : 0;
        int tBucket = threat ? 1 : 0;

        int state = dBucket * 36 + sBucket * 12 + eBucket * 4 + wBucket * 2 + tBucket;
        return Math.max(0, Math.min(TOTAL_STATES - 1, state));
    }

    private double getWallProximity() {
        double dLeft = getX();
        double dRight = getBattleFieldWidth() - getX();
        double dBottom = getY();
        double dTop = getBattleFieldHeight() - getY();
        return Math.min(Math.min(dLeft, dRight), Math.min(dBottom, dTop));
    }

    // ========================================================================
    // Feedback de Batalha e Recompensas
    // ========================================================================
    @Override
    public void onBulletHit(BulletHitEvent e) {
        double damageDealt = 4.0 * e.getBullet().getPower() + 2.0 * Math.max(0, e.getBullet().getPower() - 1.0);
        double r = damageDealt * 0.35 + 2.0;
        stepRewardAccumulator += r;
        roundAccumulatedReward += r;
        dodgeStreak = 0; // Tiro recebido zera a sequencia de esquivas
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        double damageTaken = 4.0 * e.getBullet().getPower() + 2.0 * Math.max(0, e.getBullet().getPower() - 1.0);
        double r = -(damageTaken * 0.35 + 1.5);
        stepRewardAccumulator += r;
        roundAccumulatedReward += r;

        if (!enemyWaves.isEmpty()) {
            EnemyWave w = enemyWaves.get(0);
            double hitAngle = Math.atan2(getX() - w.fireLocation.x, getY() - w.fireLocation.y);
            double offset = Utils.normalRelativeAngle(hitAngle - w.directAngle);
            double maxEscapeAngle = Math.asin(8.0 / w.bulletVelocity);
            double guessFactor = Math.max(-1.0, Math.min(1.0, (offset / maxEscapeAngle) * w.direction));

            int bin = (int) Math.round((guessFactor + 1.0) / 2.0 * (BINS_GF - 1));
            bin = Math.max(0, Math.min(BINS_GF - 1, bin));
            surfDangerProfile[bin] += 2.0;
        }
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        stepRewardAccumulator -= 6.0;
        roundAccumulatedReward -= 6.0;
        moveDirection = -moveDirection;
        lastDirectionChangeTick = getTime();
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        if (getEnergy() > e.getEnergy() + 15.0) {
            stepRewardAccumulator += 3.0;
            roundAccumulatedReward += 3.0;
        } else {
            stepRewardAccumulator -= 4.0;
            roundAccumulatedReward -= 4.0;
        }
    }

    @Override
    public void onWin(WinEvent e) {
        totalWins++;
        consecutiveLosses = 0;
        double r = 25.0;
        stepRewardAccumulator += r;
        roundAccumulatedReward += r;
        if (currentState != -1 && currentAction != -1) {
            learn(currentState, currentAction, stepRewardAccumulator, currentState, true);
        }
    }

    @Override
    public void onDeath(DeathEvent e) {
        consecutiveLosses++;
        if (consecutiveLosses >= 2) {
            epsilon = Math.min(0.25, epsilon + 0.08); // Reaquecimento adaptativo
        }

        double r = -25.0;
        stepRewardAccumulator += r;
        roundAccumulatedReward += r;
        if (currentState != -1 && currentAction != -1) {
            learn(currentState, currentAction, stepRewardAccumulator, currentState, true);
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        meleeEnemies.remove(e.getName());
        if (e.getName().equals(primaryEnemy.name)) {
            primaryEnemy.name = "";
        }
    }

    @Override
    public void onRoundEnded(RoundEndedEvent e) {
        epsilon = Math.max(MIN_EPSILON, epsilon * EPSILON_DECAY);

        System.out.printf("[OBraboBot] Round %d Concluído | Recompensa: %.2f | Epsilon: %.4f | Vitórias: %d/%d (%.1f%%)\n",
                          getRoundNum() + 1, roundAccumulatedReward, epsilon, totalWins, totalRounds,
                          (100.0 * totalWins / Math.max(1, totalRounds)));
        saveMemory();
    }

    @Override
    public void onBattleEnded(BattleEndedEvent e) {
        totalBattles++;
        saveMemory();
        System.out.printf("[OBraboBot] Batalha Concluída! Memória local salva com sucesso em obrabo_qtable.csv\n");
    }

    // ========================================================================
    // Persistência 100% Automática em Arquivo Local Único
    // ========================================================================
    public void saveMemory() {
        PrintStream writer = null;
        try {
            File dataFile = getDataFile("obrabo_qtable.csv");
            writer = new PrintStream(new RobocodeFileOutputStream(dataFile));

            writer.println("# OBraboBot Q-Table & Metadata");
            writer.printf(Locale.US, "METADATA,%d,%d,%d,%.6f\n", totalBattles, totalRounds, totalWins, epsilon);

            for (int s = 0; s < TOTAL_STATES; s++) {
                StringBuilder sb = new StringBuilder();
                sb.append(s);
                for (int a = 0; a < NUM_ACTIONS; a++) {
                    sb.append(String.format(Locale.US, ",%.5f", qTable[s][a]));
                }
                writer.println(sb.toString());
            }

            writer.printf(Locale.US, "GUN_SCORES,%d,%d,%d,%d\n", gunScores[0], gunScores[1], gunScores[2], gunScores[3]);

        } catch (IOException ex) {
            System.err.println("[OBraboBot] Aviso ao salvar memória: " + ex.getMessage());
        } finally {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        }
    }

    public void loadMemory() {
        BufferedReader reader = null;
        try {
            File dataFile = getDataFile("obrabo_qtable.csv");
            if (dataFile.exists()) {
                reader = new BufferedReader(new FileReader(dataFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] tokens = line.split(",");
                    if (tokens[0].equals("METADATA") && tokens.length >= 5) {
                        totalBattles = Integer.parseInt(tokens[1]);
                        totalRounds = Integer.parseInt(tokens[2]);
                        totalWins = Integer.parseInt(tokens[3]);
                        epsilon = Double.parseDouble(tokens[4]);
                    } else if (tokens[0].equals("GUN_SCORES") && tokens.length >= 5) {
                        gunScores[0] = Integer.parseInt(tokens[1]);
                        gunScores[1] = Integer.parseInt(tokens[2]);
                        gunScores[2] = Integer.parseInt(tokens[3]);
                        gunScores[3] = Integer.parseInt(tokens[4]);
                    } else if (tokens.length == NUM_ACTIONS + 1) {
                        int s = Integer.parseInt(tokens[0]);
                        if (s >= 0 && s < TOTAL_STATES) {
                            for (int a = 0; a < NUM_ACTIONS; a++) {
                                qTable[s][a] = Double.parseDouble(tokens[a + 1]);
                            }
                        }
                    }
                }
                System.out.printf("[OBraboBot] Memória local carregada: %d rounds | Epsilon: %.4f | Vitórias: %d\n",
                                  totalRounds, epsilon, totalWins);
            } else {
                initializeOptimisticQTable();
            }
        } catch (Exception ex) {
            initializeOptimisticQTable();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void initializeOptimisticQTable() {
        for (int s = 0; s < TOTAL_STATES; s++) {
            for (int a = 0; a < NUM_ACTIONS; a++) {
                qTable[s][a] = 1.0;
            }
        }
    }

    // ========================================================================
    // Telemetria Gráfica em Tempo Real (Ativada com botão "Paint" no Robocode)
    // ========================================================================
    @Override
    public void onPaint(Graphics2D g) {
        if (primaryEnemy == null || primaryEnemy.name.isEmpty()) return;

        // 1. Mira tática sobre o oponente
        g.setColor(new Color(255, 0, 0, 190));
        g.drawOval((int) (primaryEnemy.x - 26), (int) (primaryEnemy.y - 26), 52, 52);
        g.drawLine((int) (primaryEnemy.x - 36), (int) primaryEnemy.y, (int) (primaryEnemy.x + 36), (int) primaryEnemy.y);
        g.drawLine((int) primaryEnemy.x, (int) (primaryEnemy.y - 36), (int) primaryEnemy.x, (int) (primaryEnemy.y + 36));

        // 2. Desenho das Ondas Inimigas no ar (Wave Surfing Visualizer)
        g.setColor(new Color(255, 140, 0, 150));
        for (EnemyWave w : enemyWaves) {
            double radius = (getTime() - w.fireTime) * w.bulletVelocity;
            g.drawOval((int) (w.fireLocation.x - radius), (int) (w.fireLocation.y - radius), (int) (radius * 2), (int) (radius * 2));
        }

        // Aura Pulsante 'Instinto Superior' ao redor do chassi
        int pulseAlpha = (int) (110 + 55 * Math.sin(getTime() * 0.25));
        pulseAlpha = Math.max(40, Math.min(200, pulseAlpha));
        g.setColor(new Color(56, 189, 248, pulseAlpha));
        g.drawOval((int) (getX() - 32), (int) (getY() - 32), 64, 64);
        g.setColor(new Color(255, 255, 255, pulseAlpha / 2));
        g.drawOval((int) (getX() - 40), (int) (getY() - 40), 80, 80);

        // Icone '?' flutuante de provocacao quando desvia com perfeicao (estilo meme 10,000 hours)
        if (getTime() - lastDodgeTime < 28) {
            int floatY = (int) (getY() + 38 + (getTime() - lastDodgeTime));
            g.setColor(new Color(0, 0, 0, 220));
            g.fillRoundRect((int) getX() - 14, floatY - 22, 28, 26, 8, 8);
            g.setColor(new Color(56, 189, 248));
            g.drawRoundRect((int) getX() - 14, floatY - 22, 28, 26, 8, 8);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
            g.setColor(Color.WHITE);
            g.drawString("?", (int) getX() - 5, floatY - 3);
        }

        // 3. Painel HUD de Telemetria e Aprendizado
        g.setColor(new Color(15, 23, 42, 220));
        g.fillRect(10, 10, 295, 205);
        g.setColor(new Color(56, 189, 248));
        g.drawRect(10, 10, 295, 205);

        boolean isMelee = (getOthers() > 1);
        g.drawString(String.format("== OBRABOBOT [%s] ==", isMelee ? "MELEE MODE" : "ULTRA INSTINCT 💀"), 20, 28);
        g.setColor(new Color(244, 114, 182));
        g.drawString(String.format("Dodge Streak: %d seguidas [10k hours flex]", dodgeStreak), 20, 44);
        g.setColor(Color.WHITE);
        g.drawString(String.format(Locale.US, "Rnd: %d | Vitórias: %d (%.1f%%)", totalRounds, totalWins, 
                     (100.0 * totalWins / Math.max(1, totalRounds))), 20, 60);
        g.drawString(String.format(Locale.US, "Epsilon: %.4f | Gun Heat Inimigo: %.2f", epsilon, enemyGunHeat), 20, 78);
        g.drawString(String.format(Locale.US, "Alvo: %s (HP: %.1f)", primaryEnemy.name, primaryEnemy.energy), 20, 96);
        g.drawString(String.format(Locale.US, "Estado: %d / 143 | Ação: %s", currentState, getActionName(currentAction)), 20, 114);

        g.setColor(new Color(0, 255, 200));
        g.drawString(String.format(Locale.US, "Miras [HO:%d | LIN:%d | CIRC:%d | GF:%d]", 
                     gunScores[0], gunScores[1], gunScores[2], gunScores[3]), 20, 134);

        // Barras dinâmicas de Q-Values
        g.setColor(new Color(250, 204, 21));
        if (currentState >= 0 && currentState < TOTAL_STATES) {
            for (int a = 0; a < NUM_ACTIONS; a++) {
                int barLen = (int) Math.max(0, Math.min(38, qTable[currentState][a] * 2.2));
                g.drawString(String.format(Locale.US, "A%d: %.1f", a, qTable[currentState][a]), 20 + a * 52, 156);
                g.fillRect(20 + a * 52, 156, barLen, 7);
            }
        }
    }

    private String getActionName(int a) {
        switch (a) {
            case ACTION_ORBIT_CW: return "ORBIT_CW";
            case ACTION_ORBIT_CCW: return "ORBIT_CCW";
            case ACTION_CHARGE: return "CHARGE";
            case ACTION_RETREAT: return "RETREAT";
            case ACTION_STOP_AND_GO: return "STOP_AND_GO";
            default: return "NONE";
        }
    }

    // ========================================================================
    // Classes Utilitárias Internas
    // ========================================================================
    private static class EnemyTracker {
        String name = "";
        double x = 0;
        double y = 0;
        double distance = 500;
        double energy = 100;
        double velocity = 0;
        double lateralVelocity = 0;
        double headingRadians = 0;
        double absBearing = 0;
        double turnRate = 0;
        private double lastHeading = 0;

        void update(ScannedRobotEvent e, AdvancedRobot me) {
            name = e.getName();
            energy = e.getEnergy();
            distance = e.getDistance();
            velocity = e.getVelocity();
            headingRadians = e.getHeadingRadians();

            absBearing = Utils.normalAbsoluteAngle(me.getHeadingRadians() + e.getBearingRadians());
            x = me.getX() + Math.sin(absBearing) * distance;
            y = me.getY() + Math.cos(absBearing) * distance;

            lateralVelocity = velocity * Math.sin(headingRadians - absBearing);
            turnRate = Utils.normalRelativeAngle(headingRadians - lastHeading);
            lastHeading = headingRadians;
        }
    }

    private static class EnemyRecord {
        double x, y, energy, distance;
        void update(ScannedRobotEvent e, AdvancedRobot me) {
            energy = e.getEnergy();
            distance = e.getDistance();
            double absB = Utils.normalAbsoluteAngle(me.getHeadingRadians() + e.getBearingRadians());
            x = me.getX() + Math.sin(absB) * distance;
            y = me.getY() + Math.cos(absB) * distance;
        }
    }

    private static class EnemyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double directAngle;
        int direction;
    }

    private static class PlayerWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double directAngle;
        int direction;
        int distSlice;
        int velSlice;
        int tsdcSlice;
    }

    private static class VirtualBullet {
        double startX, startY;
        long fireTime;
        double velocity;
        double heading;
        int gunType;

        VirtualBullet(double sx, double sy, long t, double v, double h, int type) {
            this.startX = sx;
            this.startY = sy;
            this.fireTime = t;
            this.velocity = v;
            this.heading = h;
            this.gunType = type;
        }
    }
}
