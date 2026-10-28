package titan.gun;

public class DuelGun {
    private final VirtualGunsManager vgm = new VirtualGunsManager();

    public double getBestAimAngle(GunEnemy enemy, double myX, double myY, double bulletPower) {
        return vgm.getBestAngle(enemy, myX, myY, bulletPower);
    }
}
