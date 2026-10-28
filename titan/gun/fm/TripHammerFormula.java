package titan.gun.formulas;

public class TripHammerFormula {
    public static double calculateKnnWeight(double distanceDiff, double velDiff) {
        return 1.0 / (1.0 + Math.abs(distanceDiff) * 0.01 + Math.abs(velDiff) * 0.5);
    }
}
