package titan.gun.formulas;

public class AntiSurferFormula {
    public static double calculateRisk(double distance, double velocity, double headingChange) {
        return (Math.abs(velocity) * 1.2) / (distance + 1.0) + Math.abs(headingChange);
    }
}
