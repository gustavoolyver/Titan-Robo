package titan.move;

public class SurfMover {
    private final DiamondWhoosh whoosh = new DiamondWhoosh();

    public int getMoveDirection(MoveEnemy enemy, double myX, double myY, double fieldWidth, double fieldHeight) {
        double wallDist = Math.min(Math.min(myX, fieldWidth - myX), Math.min(myY, fieldHeight - myY));
        double riskClockwise = whoosh.evaluateMovementRisk(enemy.distance, wallDist, 0.5);
        double riskCounterClockwise = whoosh.evaluateMovementRisk(enemy.distance, wallDist, -0.5);
        
        return riskClockwise < riskCounterClockwise ? 1 : -1;
    }
}
