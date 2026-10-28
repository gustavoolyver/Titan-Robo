package titan.gun.formulas;

public class MeleeFormula {
    public static double calculateMeleePower(double energy, double distance, int activeEnemies) {
        if (activeEnemies > 4) return 1.0;
        if (distance < 200) return 3.0;
        return Math.min(2.0, energy / 10.0);
    }
}
