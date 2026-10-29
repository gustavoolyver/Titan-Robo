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
import titan.gun.CircularTargeting;
import titan.gun.GuessFactorTargeting;
import titan.gun.GunController;
import titan.gun.LinearTargeting;
import titan.gun.AdaptiveTargeting;
import titan.move.WaveSurfingMovement;
import titan.radar.RadarController;
import titan.stats.ShotStatistics;
import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;

/**
 * Robô principal. Só coordena os módulos (radar, movimento, arma);
 * a lógica de cada um fica no seu pacote.
 */
public class TitanVingation extends AdvancedRobot {
    private static final double MOVE_MARGIN = 40;
    private static final double TARGET_MARGIN = 18;

    private final Map<String, EnemyState> enemies = new HashMap<>();
    private EnemyState target;

    private RadarController radar;
    private WaveSurfingMovement move;
    private GunController gun;
    private ShotStatistics stats;
    private boolean learningSaved;

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

        stats = new ShotStatistics(this);
        stats.load();
        if (getRoundNum() == 0) {
            AdaptiveTargeting.load(this);
            GuessFactorTargeting.load(this);
            WaveSurfingMovement.load(this);
        }

        radar = new RadarController(this);
        move = new WaveSurfingMovement(this, new BattlefieldBounds(width, height, MOVE_MARGIN));
        BattlefieldBounds targetField = new BattlefieldBounds(width, height, TARGET_MARGIN);
        // a primeira mira da lista é a usada enquanto não há dados para comparar
        gun = new GunController(this, targetField,
                new AdaptiveTargeting(targetField,
                        new CircularTargeting(),
                        new LinearTargeting(),
                        new GuessFactorTargeting()),
                stats);

        radar.initRound();
        gun.initRound();
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double bearing = getHeadingRadians() + e.getBearingRadians();
        double x = getX() + Math.sin(bearing) * e.getDistance();
        double y = getY() + Math.cos(bearing) * e.getDistance();

        EnemyState enemy = enemies.get(e.getName());
        if (enemy == null) {
            enemy = new EnemyState(e.getName());
            enemies.put(e.getName(), enemy);
        }
        enemy.update(x, y, e.getHeadingRadians(), e.getVelocity(),
                e.getDistance(), e.getEnergy(), getTime());

        move.onScannedRobot(enemy);
        gun.onScannedRobot(enemy);
        selectTarget(enemy);
    }

    private void selectTarget(EnemyState enemy) {
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
        move.onHitByBullet(e);
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

    private void saveLearning() {
        if (learningSaved || stats == null) return;
        learningSaved = true;
        stats.save();
        AdaptiveTargeting.save(this);
        GuessFactorTargeting.save(this);
        WaveSurfingMovement.save(this);
    }

    @Override
    public void onRoundEnded(robocode.RoundEndedEvent e) {
        saveLearning();
    }

    @Override
    public void onHitRobot(robocode.HitRobotEvent e) {
        if (move != null) move.onHitWall();
        setBack(60);
    }

    @Override
    public void onWin(WinEvent e) {
        saveLearning();
        setTurnRight(360);
    }

    @Override
    public void onDeath(DeathEvent e) {
        saveLearning();
    }
}
