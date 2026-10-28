package titan.gun;

import titan.gun.formulas.MeleeFormula;

public class MeleeGun {
    public double getAimAngle(GunEnemy enemy, double myX, double myY, double myEnergy, int enemyCount) {
        double power = MeleeFormula.calculateMeleePower(myEnergy, enemy.distance, enemyCount);
        return Math.atan2(enemy.x - myX, enemy.y - myY);
    }
}
