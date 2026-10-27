package titan.stats;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import robocode.AdvancedRobot;
import robocode.RobocodeFileOutputStream;

/** Contagem de tiros disparados e acertados, guardada entre batalhas. */
public class ShotStats {
    private static final String FILE_NAME = "stats.dat";

    private final AdvancedRobot robot;
    private int fired;
    private int hit;

    public ShotStats(AdvancedRobot robot) {
        this.robot = robot;
    }

    public void shotFired() {
        fired++;
    }

    public void shotHit() {
        hit++;
    }

    public int getFired() {
        return fired;
    }

    public int getHit() {
        return hit;
    }

    public void load() {
        try (DataInputStream in = new DataInputStream(
                new FileInputStream(robot.getDataFile(FILE_NAME)))) {
            fired = in.readInt();
            hit = in.readInt();
        } catch (IOException e) {
            fired = 0;
            hit = 0;
        }
    }

    /** O Robocode só permite gravar arquivos de dados via RobocodeFileOutputStream. */
    public void save() {
        try (DataOutputStream out = new DataOutputStream(
                new RobocodeFileOutputStream(robot.getDataFile(FILE_NAME)))) {
            out.writeInt(fired);
            out.writeInt(hit);
        } catch (IOException e) {
            // sem estatísticas salvas; o robô continua funcionando
        }
    }
}
