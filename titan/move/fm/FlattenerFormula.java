package titan.move.formulas;

public class FlattenerFormula {
    public static double flattenRisk(double gf, double hitFrequency) {
        return 1.0 / (1.0 + hitFrequency * 10.0);
    }
}
