package titan.gun;

import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;
import robocode.Bullet;
import java.awt.geom.Point2D;

public class GunController {
    private AdvancedRobot robot;
    private GuessFactorTargeting guessFactorTargeting;
    private VirtualGuns virtualGuns;
    
    // Controlo de desempenho para ajuste de potência
    private double totalShots = 0;
    private double totalHits = 0;

    public GunController(AdvancedRobot robot) {
        this.robot = robot;
        this.guessFactorTargeting = new GuessFactorTargeting(robot);
        this.virtualGuns = new VirtualGuns(robot);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        guessFactorTargeting.onScannedRobot(e);
        virtualGuns.onScannedRobot(e);
    }

    public void onBulletHit(robocode.BulletHitEvent e) {
        totalHits++;
        totalShots++;
        virtualGuns.onBulletHit(e);
    }

    public void onBulletMissed(robocode.BulletMissedEvent e) {
        totalShots++;
        virtualGuns.onBulletMissed(e);
    }

    public void onTick() {
        guessFactorTargeting.onTick();
        virtualGuns.onTick();
    }

    public void fire(ScannedRobotEvent e) {
        double enemyDistance = e.getDistance();
        
        // Se o inimigo estiver muito perto, usa potência máxima para estrangular
        if (enemyDistance < 100) {
            double power = Math.min(3.0, robot.getEnergy() - 1);
            if (power > 0.1) {
                Bullet b = robot.fireBullet(power);
                if (b != null) guessFactorTargeting.registerBullet(b, e);
            }
            return;
        }

        // --- CÁLCULO DE POTÊNCIA DINÂMICA (Ronda 6) ---
        double basePower = calculateDynamicPower(e);
        
        // Garante que nunca gastamos mais energia do que o necessário para matar ou do que temos disponível
        double maxPowerForEnergy = robot.getEnergy() - 1;
        double maxPowerToKill = (e.getEnergy() + 4) / 4; // Estimativa para poupar energia se o inimigo estiver fraco
        
        double finalPower = Math.min(basePower, maxPowerForEnergy);
        finalPower = Math.min(finalPower, maxPowerToKill);
        finalPower = Math.max(0.1, Math.min(3.0, finalPower));

        // Dispara utilizando a melhor arma selecionada pelas VirtualGuns
        Bullet bullet = virtualGuns.fire(e, finalPower);
        if (bullet != null) {
            guessFactorTargeting.registerBullet(bullet, e);
        }
    }

    private double calculateDynamicPower(ScannedRobotEvent e) {
        double hitRate = (totalShots > 0) ? (totalHits / totalShots) : 0.5;
        double ourEnergy = robot.getEnergy();
        double enemyEnergy = e.getEnergy();

        // Se estamos com pouca energia comparado ao inimigo, conservamos energia com tiros mais fracos e seguros
        if (ourEnergy < enemyEnergy - 20) {
            return 1.2;
        }

        // Se a nossa taxa de acerto está alta, podemos arriscar tiros mais pesados
        if (hitRate > 0.4) {
            return 2.4;
        }

        // Padrão equilibrado baseado na distância
        if (e.getDistance() > 400) {
            return 1.5;
        } else {
            return 2.0;
        }
    }

    public void notifyHitByBullet(robocode.BulletBulletEvent e) {
        // Repassado caso necessário para estatísticas de defesa
    }
}
