package titan.gun;

import titan.gun.formulas.AntiSurferFormula;

public class AntiSurferGun {
    public double getAimAngle(GunEnemy enemy, double myX, double myY, double bulletPower) {
        double risk = AntiSurferFormula.calculateRisk(enemy.distance, enemy.velocity, 0.05);
        double directAngle = Math.atan2(enemy.x - myX, enemy.y - myY);
        return directAngle + (risk > 0.5 ? 0.05 : -0.05);
    }
}
