package titan.stats;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import robocode.AdvancedRobot;
import robocode.RobocodeFileOutputStream;

/** Contagem de tiros disparados e acertados, guardada entre batalhas. */
public class ShotStatistics {
    private static final String FILE_NAME = "stats.dat";

    private final AdvancedRobot robot;
    private int fired;
    private int hit;

    public ShotStatistics(AdvancedRobot robot) {
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
            int loadedFired = in.readInt();
            int loadedHit = in.readInt();
            if (loadedFired < 0 || loadedHit < 0 || loadedHit > loadedFired)
                throw new IOException("Contadores invalidos");
            fired = loadedFired;
            hit = loadedHit;
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
            robot.out.println("Titan: falha ao salvar estatisticas: " + e.getMessage());
        }
    }
}
