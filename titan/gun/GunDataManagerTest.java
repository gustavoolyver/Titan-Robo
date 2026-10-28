package titan.gun;

public class GunDataManagerTest {
    public static void main(String[] args) {
        GunDataManager gdm = new GunDataManager();
        gdm.recordHit("TestTarget", 15);
        System.out.println("GunDataManagerTest passed.");
    }
}
