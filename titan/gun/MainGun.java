package titan.gun;

import titan.gun.formulas.MainGunFormula;

public class MainGun {
    public double getAimAngle(GunEnemy enemy, double myX, double myY, double bulletPower) {
        double directAngle = Math.atan2(enemy.x - myX, enemy.y - myY);
        double gf = MainGunFormula.calculateGuessFactor(enemy.heading, enemy.velocity, bulletPower);
        return directAngle + gf * 0.5;
    }
}
