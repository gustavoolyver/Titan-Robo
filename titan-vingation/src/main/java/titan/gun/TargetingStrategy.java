package titan.gun;

import titan.utils.BattlefieldBounds;
import titan.utils.EnemyState;

/** Estratégia de mira: decide para onde apontar o canhão. */
public interface TargetingStrategy {
    /** Ângulo absoluto (radianos) para onde apontar. */
    double aimAngle(EnemyState enemy, double myX, double myY,
                    double bulletSpeed, BattlefieldBounds field);

    /** Chamado a cada turno com o alvo atual. Mira que aprende usa isto; as simples ignoram. */
    default void onTick(EnemyState enemy, long time) {
    }

    /**
     * Chamado todo turno com a velocidade de bala que usaríamos agora, mesmo sem
     * atirar. Miras que aprendem criam "tiros virtuais" aqui.
     */
    default void onAim(EnemyState enemy, double myX, double myY,
                       double bulletSpeed, long time) {
    }

    /** Chamado logo depois que um tiro é disparado. */
    default void onFire(EnemyState enemy, double myX, double myY,
                        double bulletSpeed, long time) {
    }
}
