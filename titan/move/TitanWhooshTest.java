package titan.move;

public class TitanWhooshTest {
    public static void main(String[] args) {
        TitanWhoosh dw = new TitnWhoosh();
        System.out.println("Risk: " + dw.evaluateMovementRisk(300, 100, 0.2));
    }
}
