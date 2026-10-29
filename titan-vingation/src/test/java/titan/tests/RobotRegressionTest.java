package titan.tests;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import robocode.*;
import titan.gun.*;
import titan.move.WaveSurfingMovement;
import titan.radar.RadarController;
import titan.stats.*;
import titan.utils.*;

/** Local regression checks; no battle engine or third-party test framework required. */
public final class RobotRegressionTest {
    private static int passed;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        FakeRobot robot = new FakeRobot();
        try {
            BattlefieldBounds field = new BattlefieldBounds(800, 600, 18);
            check(field.contains(18,18) && !field.contains(17,18), "arena boundary");
            check(field.clampX(900)==782 && field.clampY(-1)==18, "arena clamping");
            check(Math.abs(Geometry.absoluteBearing(0,0,1,0)-Math.PI/2)<1e-9,"north-based bearings");
            EnemyState enemy = new EnemyState("sample.Target");
            enemy.update(400,500,Math.toRadians(359),0,200,100,0);
            enemy.update(400,500,Math.toRadians(1),0,200,98,1);
            check(Math.abs(enemy.headingChange-Math.toRadians(2))<1e-9,"heading wrap");
            check(enemy.energyDrop()==2,"energy drop");
            enemy.update(400,500,0,0,200,98,robot.time);
            check(Math.abs(new LinearTargeting().aimAngle(enemy,400,300,14,field))<1e-9,"stationary linear aim");
            enemy.update(400,500,Math.PI/2,8,200,98,robot.time);
            check(new LinearTargeting().aimAngle(enemy,400,300,14,field)>0,"moving target lead");
            double circular = new CircularTargeting().aimAngle(enemy,400,300,14,field);
            check(Double.isFinite(circular),"finite circular aim");

            RadarController radar = new RadarController(robot);
            radar.execute(enemy);
            check(Double.isFinite(robot.radarTurn),"duel radar lock");
            robot.others=3; radar.execute(enemy);
            check(Double.isInfinite(robot.radarTurn),"melee radar sweep");
            robot.others=1; robot.time+=10; radar.execute(enemy);
            check(Double.isInfinite(robot.radarTurn),"stale radar recovery");
            enemy.lastSeen=robot.time;
            TargetingStrategy direct = new TargetingStrategy() {
                public double aimAngle(EnemyState e,double x,double y,double s,BattlefieldBounds f) {return 0;}
            };
            ShotStatistics stats = new ShotStatistics(robot);
            GunController gun = new GunController(robot,field,direct,stats);
            enemy.energy=0.4;
            gun.execute(enemy);
            check(Math.abs(robot.power-0.1)<1e-9,"finishing power");
            check(stats.getFired()==1,"real shot accounting");
            robot.power=-1; robot.gunHeading=0.04;
            enemy.energy=100; enemy.y=850;
            gun.execute(enemy);
            check(robot.power==-1,"distance-based firing tolerance");
            robot.gunHeading=0; robot.energy=0.1; gun.execute(enemy);
            check(robot.power==-1,"preserve last energy");
            robot.energy=100; enemy.lastSeen=robot.time-5; gun.execute(enemy);
            check(robot.power==-1,"stale target cannot fire");
            enemy.lastSeen=robot.time; enemy.y=500;

            TargetingStrategy wrong = new TargetingStrategy() {
                public double aimAngle(EnemyState e,double x,double y,double s,BattlefieldBounds f) {return 1;}
            };
            AdaptiveTargeting adaptive=new AdaptiveTargeting(field,wrong,direct,new GuessFactorTargeting());
            enemy.velocity=0;
            adaptive.onAim(enemy,400,300,14,robot.time);
            robot.time+=20; enemy.lastSeen=robot.time;
            adaptive.onTick(enemy,robot.time);
            check(Math.abs(adaptive.aimAngle(enemy,400,300,14,field))<1e-9,"virtual gun learns correct strategy");
            GuessFactorTargeting gf = new GuessFactorTargeting();
            for(int t=0;t<150;t++) {
                enemy.lastSeen=robot.time;
                gf.onTick(enemy,robot.time); gf.onAim(enemy,400,300,14,robot.time);
                robot.time++;
            }
            check(Double.isFinite(gf.aimAngle(enemy,400,300,14,field)),"trained guess factor remains finite");
            Field cache=GuessFactorTargeting.class.getDeclaredField("STATS"); cache.setAccessible(true);
            for(int n=0;n<30;n++) {
                EnemyState other=new EnemyState("enemy"+n); other.update(400,500,0,0,200,100,robot.time);
                gf.aimAngle(other,400,300,14,field);
            }
            check(((Map<?,?>)cache.get(null)).size()==12,"bounded opponent cache");

            Map<String,double[][]> data=new LinkedHashMap<String,double[][]>();
            data.put("sample",new double[][]{{1,2},{3,4}});
            Method encode=LearningRepository.class.getDeclaredMethod("encode",Map.class,List.class);
            encode.setAccessible(true);
            byte[] bytes=(byte[])encode.invoke(null,data,new ArrayList<String>(data.keySet()));
            Files.write(robot.directory.resolve("memory.dat"),bytes);
            Map<String,double[][]> loaded=LearningRepository.load(robot,"memory.dat",2,2);
            check(loaded!=null && loaded.get("sample")[1][1]==4,"compressed learning round trip");
            check(LearningRepository.load(robot,"memory.dat",3,2)==null,"reject incompatible dimensions");
            bytes[bytes.length-5]^=32; Files.write(robot.directory.resolve("memory.dat"),bytes);
            check(LearningRepository.load(robot,"memory.dat",2,2)==null,"reject bad checksum");
            check(LearningRepository.load(robot,"missing.dat",2,2).isEmpty(),"missing learning starts clean");
            data.get("sample")[0][0]=Double.NaN;
            boolean rejected=false;
            try {encode.invoke(null,data,new ArrayList<String>(data.keySet()));}
            catch(InvocationTargetException e) {rejected=e.getCause() instanceof IOException;}
            check(rejected,"reject non-finite learning");

            WaveSurfingMovement movement=new WaveSurfingMovement(robot,new BattlefieldBounds(800,600,40));
            enemy.update(400,500,0,0,200,100,robot.time); movement.onScannedRobot(enemy);
            robot.time++; enemy.update(400,500,0,0,200,98,robot.time); movement.onScannedRobot(enemy);
            movement.execute(enemy);
            check(Double.isFinite(robot.bodyTurn) && Math.abs(robot.ahead)==100,"surfing produces movement");
            enemy.energy=0; robot.time+=100; movement.execute(enemy);
            check(Double.isFinite(robot.bodyTurn),"approach powerless enemy");
            movement.execute(null);
            System.out.println("PASS: "+passed+" regression checks (mock robot; no live battles).");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(robot.directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});
            }
        }
    }
    private static final class FakeRobot extends AdvancedRobot {
        final Path directory;
        long time=10;
        int others=1;
        double energy=100,gunHeading,power=-1,radarTurn,bodyTurn,ahead;
        FakeRobot() throws IOException {out=System.out;directory=Files.createTempDirectory("titan-tests-");}
        @Override public double getX(){return 400;}
        @Override public double getY(){return 300;}
        @Override public double getBattleFieldWidth(){return 800;}
        @Override public double getBattleFieldHeight(){return 600;}
        @Override public long getTime(){return time;}
        @Override public int getOthers(){return others;}
        @Override public double getHeadingRadians(){return 0;}
        @Override public double getGunHeadingRadians(){return gunHeading;}
        @Override public double getRadarHeadingRadians(){return 0;}
        @Override public double getVelocity(){return 0;}
        @Override public double getEnergy(){return energy;}
        @Override public double getGunHeat(){return 0;}
        @Override public void setTurnGunRightRadians(double v){}
        @Override public void setTurnRadarRightRadians(double v){radarTurn=v;}
        @Override public void setTurnRightRadians(double v){bodyTurn=v;}
        @Override public void setAhead(double v){ahead=v;}
        @Override public void setMaxVelocity(double v){}
        @Override public File getDataFile(String name){return directory.resolve(name).toFile();}
        @Override public Bullet setFireBullet(double v){power=v;return new Bullet(0,400,300,v,"titan","",true,0);}
    }
}
