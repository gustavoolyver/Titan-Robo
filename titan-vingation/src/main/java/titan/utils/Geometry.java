package titan.utils;

/** Funções geométricas puras (sem dependência do Robocode). */
public final class Geometry {
    private Geometry() {
    }

    /** Ângulo absoluto (radianos; 0 = norte, sentido horário) do ponto 1 até o ponto 2. */
    public static double absoluteBearing(double x1, double y1, double x2, double y2) {
        return Math.atan2(x2 - x1, y2 - y1);
    }
}
