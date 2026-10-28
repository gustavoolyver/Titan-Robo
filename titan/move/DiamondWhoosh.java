package titan.move;

import titan.move.formulas.FlattenerFormula;
import titan.move.formulas.NormalFormula;

public class DiamondWhoosh {
    public double evaluateMovementRisk(double distance, double wallDist, double gf) {
        double normalRisk = NormalFormula.calculateStandardRisk(distance, gf);
        double flatRisk = FlattenerFormula.flattenRisk(gf, 0.1);
        return normalRisk * 0.7 + flatRisk * 0.3 + (wallDist < 50 ? 1000 : 0);
    }
}
