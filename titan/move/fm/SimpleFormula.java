package titan.move.formulas;

public class SimpleFormula {
    public static double calculateRisk(double distToWall, double distToEnemy) {
        return (100.0 / (distToWall + 1.0)) + (200.0 / (distToEnemy + 1.0));
    }
}
