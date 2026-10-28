package titan.move;

public class TitanWhooshTest {
    public static void main(String[] args) {
        DiamondWhoosh dw = new DiamondWhoosh();
        System.out.println("Risk: " + dw.evaluateMovementRisk(300, 100, 0.2));
    }
}
