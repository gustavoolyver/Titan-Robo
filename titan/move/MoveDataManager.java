package titan.move;

import java.util.HashMap;
import java.util.Map;

public class MoveDataManager {
    private final Map<String, double[]> surfStats = new HashMap<>();

    public double[] getStats(String enemyName) {
        return surfStats.computeIfAbsent(enemyName, k -> new double[47]);
    }
}
