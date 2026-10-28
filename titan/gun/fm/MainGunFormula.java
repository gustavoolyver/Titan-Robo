package titan.gun.formulas;

public class MainGunFormula {
    public static double calculateGuessFactor(double enemyHeading, double enemyVelocity, double bulletPower) {
        double maxEscapeAngle = Math.asin(8.0 / (20.0 - 3.0 * bulletPower));
        return (enemyVelocity / 8.0) * Math.sin(enemyHeading) / maxEscapeAngle;
    }
}
