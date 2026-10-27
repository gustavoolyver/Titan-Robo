package titan.gun;

import titan.utils.BattleField;
import titan.utils.Enemy;

/** Estratégia de mira: decide para onde apontar o canhão. */
public interface Targeting {
    /** Ângulo absoluto (radianos) para onde apontar. */
    double aimAngle(Enemy enemy, double myX, double myY,
                    double bulletSpeed, BattleField field);

    /** Chamado a cada turno com o alvo atual. Mira que aprende usa isto; as simples ignoram. */
    default void onTick(Enemy enemy, long time) {
    }

    /**
     * Chamado todo turno com a velocidade de bala que usaríamos agora, mesmo sem
     * atirar. Miras que aprendem criam "tiros virtuais" aqui.
     */
    default void onAim(Enemy enemy, double myX, double myY,
                       double bulletSpeed, long time) {
    }

    /** Chamado logo depois que um tiro é disparado. */
    default void onFire(Enemy enemy, double myX, double myY,
                        double bulletSpeed, long time) {
    }
}
