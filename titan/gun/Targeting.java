package titan.gun;

import titan.utils.BattleField;
import titan.utils.Enemy;

/** Estratégia de mira: decide para onde apontar o canhão. */
public interface Targeting {
    /** Ângulo absoluto (radianos) para onde apontar. */
    double aimAngle(Enemy enemy, double myX, double myY,
                    double bulletSpeed, BattleField field);
}
