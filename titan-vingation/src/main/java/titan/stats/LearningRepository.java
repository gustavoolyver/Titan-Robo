package titan.stats;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import robocode.AdvancedRobot;
import robocode.RobocodeFileOutputStream;

/** Memoria compactada e limitada para respeitar a cota do Robocode. */
public final class LearningRepository {
    private static final int MAGIC = 0x54495431;
    private static final int MAX_ENEMIES = 12;
    private static final int MAX_FILE_BYTES = 18000;

    private LearningRepository() { }

    /** null significa leitura invalida; o chamador pode manter a memoria atual. */
    public static Map<String, double[][]> load(AdvancedRobot robot, String fileName,
                                               int rows, int columns) {
        File file = robot.getDataFile(fileName);
        Map<String, double[][]> result = new java.util.LinkedHashMap<String, double[][]>();
        if (!file.exists()) return result;
        if (file.length() > 45000) return null;
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != rows
                    || in.readInt() != columns) throw new IOException("Formato invalido");
            int count = in.readInt();
            if (count < 0 || count > MAX_ENEMIES) throw new IOException("Contagem invalida");
            for (int n = 0; n < count; n++) {
                String name = in.readUTF();
                double[][] matrix = new double[rows][columns];
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < columns; c++) {
                        double value = in.readDouble();
                        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0)
                            throw new IOException("Valor invalido");
                        matrix[r][c] = value;
                    }
                }
                result.put(name, matrix);
            }
            // Le ate o final para validar tambem o checksum do GZIP.
            if (in.read() != -1) throw new IOException("Dados extras");
            return result;
        } catch (IOException e) {
            robot.out.println("Titan: memoria ignorada em " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    public static void save(AdvancedRobot robot, String fileName,
                            Map<String, double[][]> data) {
        if (data.isEmpty()) return;
        List<String> names = new ArrayList<String>(data.keySet());
        // Limite explicito: ate 12 adversarios por modulo.
        while (names.size() > MAX_ENEMIES) names.remove(0);
        try {
            byte[] bytes = encode(data, names);
            while ((bytes.length > MAX_FILE_BYTES
                    || bytes.length > robot.getDataQuotaAvailable() + robot.getDataFile(fileName).length())
                    && names.size() > 1) {
                names.remove(0);
                bytes = encode(data, names);
            }
            File file = robot.getDataFile(fileName);
            if (bytes.length > MAX_FILE_BYTES
                    || bytes.length > robot.getDataQuotaAvailable() + file.length()) {
                robot.out.println("Titan: sem espaco para salvar " + fileName);
                return;
            }
            try (RobocodeFileOutputStream out = new RobocodeFileOutputStream(file)) {
                out.write(bytes);
            }
        } catch (IOException e) {
            robot.out.println("Titan: nao foi possivel salvar " + fileName + ": " + e.getMessage());
        }
    }

    private static byte[] encode(Map<String, double[][]> data, List<String> names)
            throws IOException {
        double[][] first = data.get(names.get(0));
        int rows = first.length;
        int columns = first[0].length;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.writeInt(MAGIC);
            out.writeInt(rows);
            out.writeInt(columns);
            out.writeInt(names.size());
            for (String name : names) {
                out.writeUTF(name);
                double[][] matrix = data.get(name);
                if (matrix.length != rows) throw new IOException("Matriz invalida");
                for (double[] row : matrix) {
                    if (row.length != columns) throw new IOException("Linha invalida");
                    for (double value : row) {
                        if (!Double.isFinite(value) || value < 0) throw new IOException("Valor invalido");
                        out.writeDouble(value);
                    }
                }
            }
        }
        return buffer.toByteArray();
    }
}
