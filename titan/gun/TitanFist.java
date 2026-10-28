package titan.gun;

public class TitanFist {
    private final AntiSurferGun antiSurferGun = new AntiSurferGun();
    private final MainGun mainGun = new MainGun();

    public double getAimAngle(GunEnemy enemy, double myX, double myY, double bulletPower) {
        if (enemy.distance < 300) {
            return antiSurferGun.getAimAngle(enemy, myX, myY, bulletPower);
        }
        return mainGun.getAimAngle(enemy, myX, myY, bulletPower);
    }
}
