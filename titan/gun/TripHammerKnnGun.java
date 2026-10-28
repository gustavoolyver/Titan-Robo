package titan.gun;

import titan.gun.formulas.TripHammerFormula;

public class TripHammerKnnGun {
    public double getKnnAimAngle(GunEnemy enemy, double myX, double myY) {
        double weight = TripHammerFormula.calculateKnnWeight(enemy.distance, enemy.velocity);
        double baseAngle = Math.atan2(enemy.x - myX, enemy.y - myY);
        return baseAngle + (weight * 0.02);
    }
}
