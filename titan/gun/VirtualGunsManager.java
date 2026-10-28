package titan.gun;

public class VirtualGunsManager {
    private final MainGun mainGun = new MainGun();
    private final AntiSurferGun antiSurferGun = new AntiSurferGun();
    private final TripHammerKnnGun knnGun = new TripHammerKnnGun();

    public double getBestAngle(GunEnemy enemy, double myX, double myY, double bulletPower) {
        // Dynamic selection based on distance and enemy velocity
        if (Math.abs(enemy.velocity) > 6.0) {
            return antiSurferGun.getAimAngle(enemy, myX, myY, bulletPower);
        } else if (enemy.distance < 400) {
            return knnGun.getKnnAimAngle(enemy, myX, myY);
        }
        return mainGun.getAimAngle(enemy, myX, myY, bulletPower);
    }
}
