package titan.move.formulas;

public class NormalFormula {
    public static double calculateStandardRisk(double distance, double gf) {
        return Math.exp(-Math.pow(gf, 2) / 0.5) / (distance + 1.0);
    }
}
