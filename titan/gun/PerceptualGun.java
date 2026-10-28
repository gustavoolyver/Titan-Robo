package titan.gun;

public class PerceptualGun {
    public double predictAngle(GunEnemy enemy, double myX, double myY) {
        return Math.atan2(enemy.x - myX, enemy.y - myY) + (Math.sin(enemy.heading) * 0.1);
    }
}
