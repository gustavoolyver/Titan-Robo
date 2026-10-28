package titan.gun;

import java.util.HashMap;
import java.util.Map;

public class GunDataManager {
    private final Map<String, double[]> gfData = new HashMap<>();

    public double[] getGFBands(String enemyName) {
        return gfData.computeIfAbsent(enemyName, k -> new double[31]);
    }

    public void recordHit(String enemyName, int index) {
        double[] bands = getGFBands(enemyName);
        if (index >= 0 && index < bands.length) {
            bands[index] += 1.0;
        }
    }
}
