package titan.utils;

/** Campo de batalha com uma margem de segurança em relação às paredes. */
public class BattlefieldBounds {
    private final double width;
    private final double height;
    private final double margin;

    public BattlefieldBounds(double width, double height, double margin) {
        this.width = width;
        this.height = height;
        this.margin = margin;
    }

    public boolean contains(double x, double y) {
        return x >= margin && y >= margin
                && x <= width - margin && y <= height - margin;
    }

    public double clampX(double x) {
        return Math.max(margin, Math.min(width - margin, x));
    }

    public double clampY(double y) {
        return Math.max(margin, Math.min(height - margin, y));
    }
}
