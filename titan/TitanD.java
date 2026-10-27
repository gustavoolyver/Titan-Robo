package titan;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import robocode.AdvancedRobot;
import robocode.BulletHitEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.WinEvent;
import titan.gun.GuessFactorTargeting;
import titan.gun.GunController;
import titan.move.OrbitMovement;
import titan.radar.RadarLock;
import titan.stats.ShotStats;
import titan.utils.BattleField;
import titan.utils.Enemy;

/**
 * Robô principal. Só coordena os módulos (radar, movimento, arma);
 * a lógica de cada um fica no seu pacote.
 */
public class TitanD extends AdvancedRobot {
    private static final double MOVE_MARGIN = 40;
    private static final double TARGET_MARGIN = 18;

    private final Map<String, Enemy> enemies = new HashMap<>();
    private Enemy target;

    private RadarLock radar;
    private OrbitMovement move;
    private GunController gun;
    private ShotStats stats;

    @Override
    public void run() {
        setColors(Color.BLACK, Color.CYAN, Color.GREEN);
        setBulletColor(Color.ORANGE);
        initComponents();

        while (true) {
            move.execute(target);
            gun.execute(target);
            radar.execute(target);
            execute();
        }
    }

    // os componentes são criados aqui (e não no construtor) porque o campo de
    // batalha só pode ser consultado depois que o round começa
    private void initComponents() {
        double width = getBattleFieldWidth();
        double height = getBattleFieldHeight();

        stats = new ShotStats(this);
        stats.load();

        radar = new RadarLock(this);
        move = new OrbitMovement(this, new BattleField(width, height, MOVE_MARGIN));
        gun = new GunController(this,
                new BattleField(width, height, TARGET_MARGIN),
                new GuessFactorTargeting(), stats);

        radar.initRound();
        gun.initRound();
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double bearing = getHeadingRadians() + e.getBearingRadians();
        double x = getX() + Math.sin(bearing) * e.getDistance();
        double y = getY() + Math.cos(bearing) * e.getDistance();

        Enemy enemy = enemies.get(e.getName());
        if (enemy == null) {
            enemy = new Enemy(e.getName());
            enemies.put(e.getName(), enemy);
        }
        enemy.update(x, y, e.getHeadingRadians(), e.getVelocity(),
                e.getDistance(), e.getEnergy(), getTime());

        move.onScannedRobot(enemy);
        selectTarget(enemy);
    }

    private void selectTarget(Enemy enemy) {
        if (target == null
                || target.name.equals(enemy.name)
                || getTime() - target.lastSeen > 2
                || enemy.distance < target.distance) {
            target = enemy;
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        stats.shotHit();
        move.onBulletHit(e);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        move.onHitByBullet();
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        move.onHitWall();
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());
        if (target != null && target.name.equals(e.getName())) {
            target = null;
        }
    }

    @Override
    public void onWin(WinEvent e) {
        stats.save();
        setTurnRight(360);
    }

    @Override
    public void onDeath(DeathEvent e) {
        stats.save();
    }
}
