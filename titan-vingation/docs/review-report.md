# Revisão do projeto

## Resultado

O ZIP original contém 48 fontes Java, uma dependência JAR e dois arquivos de preenchimento. A cadeia de dependências a partir de `titan.TitanV3` utiliza 15 fontes Java. Foram removidos 35 arquivos: 33 fontes fora dessa cadeia (incluindo testes sem asserções) e dois preenchimentos. A dependência foi preservada.

## Arquivos preservados e nomes finais

Os nomes que já descreviam corretamente sua função foram mantidos. Todos os imports e referências das classes renomeadas foram atualizados. O pacote `titan` preserva a identidade do projeto. Os nomes dos arquivos de aprendizado permanecem estáveis para migração.

| Original | Entrega |
| --- | --- |
| `titan/TitanV3.java` | `src/main/java/titan/TitanVingation.java` |
| `titan/gun/GunController.java` | `src/main/java/titan/gun/GunController.java` |
| `titan/gun/VirtualGuns.java` | `src/main/java/titan/gun/AdaptiveTargeting.java` |
| `titan/gun/Targeting.java` | `src/main/java/titan/gun/TargetingStrategy.java` |
| `titan/gun/PositionPredictor.java` | `src/main/java/titan/gun/MotionPredictor.java` |
| `titan/gun/CircularTargeting.java` | `src/main/java/titan/gun/CircularTargeting.java` |
| `titan/gun/LinearTargeting.java` | `src/main/java/titan/gun/LinearTargeting.java` |
| `titan/gun/GuessFactorTargeting.java` | `src/main/java/titan/gun/GuessFactorTargeting.java` |
| `titan/move/SurfMovement.java` | `src/main/java/titan/move/WaveSurfingMovement.java` |
| `titan/radar/RadarLock.java` | `src/main/java/titan/radar/RadarController.java` |
| `titan/stats/LearningStore.java` | `src/main/java/titan/stats/LearningRepository.java` |
| `titan/stats/ShotStats.java` | `src/main/java/titan/stats/ShotStatistics.java` |
| `titan/utils/Enemy.java` | `src/main/java/titan/utils/EnemyState.java` |
| `titan/utils/BattleField.java` | `src/main/java/titan/utils/BattlefieldBounds.java` |
| `titan/utils/Geometry.java` | `src/main/java/titan/utils/Geometry.java` |
| `titan/libs/robocode.jar` | `lib/robocode-api.jar` |

## Arquivos removidos

Sem referência a partir do robô principal; não eram usados na execução entregue pelo ZIP original. O arquivo original enviado permanece intacto.

| Arquivo | Motivo |
| --- | --- |
| `titan/gun/GunDataManager.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/AntiSurferGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/VirtualGunsManager.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/GunDataManagerTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/gun/TitanFistTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/gun/DuelGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/MainGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/PerceptualGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/GunEnemyTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/gun/TitanFist.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/MeleeGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/FireListener.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/GunEnemy.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/TripHammerKnnGun.java` | Módulo fora da cadeia ativa de execução. |
| `titan/test/DnaSequenceTests.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/test/8888888888888` | Arquivo de preenchimento sem código. |
| `titan/test/DnaStringTests.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/radar/TitanRd.java` | Módulo fora da cadeia ativa de execução. |
| `titan/move/MoveEnemy.java` | Módulo fora da cadeia ativa de execução. |
| `titan/move/TitanWhooshTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/move/MoveDataManagerTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/move/SurfMover.java` | Módulo fora da cadeia ativa de execução. Também referencia `DiamondWhoosh`, classe inexistente no ZIP. |
| `titan/move/TitanWhoosh.java` | Módulo fora da cadeia ativa de execução. |
| `titan/move/SurfMoverTest.java` | Teste sem asserções; apenas instanciava objetos ou imprimia mensagens. |
| `titan/move/MoveDataManager.java` | Módulo fora da cadeia ativa de execução. |
| `titan/move/OrbitMovement.java` | Módulo fora da cadeia ativa de execução. |
| `titan/move/MeleeMover.java` | Módulo fora da cadeia ativa de execução. |
| `titan/gun/fm/AntiSurferFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/gun/fm/MeleeFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/gun/fm/MainGunFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/gun/fm/TripHammerFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/move/fm/88888888` | Arquivo de preenchimento sem código. |
| `titan/move/fm/NormalFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/move/fm/FlattenerFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |
| `titan/move/fm/SimpleFormula.java` | Módulo fora da cadeia ativa de execução. O diretório também divergia do pacote Java declarado (`formulas`). |

## Correções e melhorias

- Estrutura de fontes, recursos, testes, dependência e distribuição separados. Metadados do Robocode e scripts de compilação adicionados.
- Classe principal padronizada para `TitanVingation` e artefato para `titan-vingation.jar`.
- Radar faz varredura contínua quando existem vários adversários; em duelo mantém o travamento e recupera alvos perdidos.
- Tolerância para disparar diminui com a distância. A potência respeita energia disponível e dano necessário para finalizar o alvo.
- Ondas virtuais são pontuadas nos scans de cada adversário, inclusive quando ele não é o alvo selecionado.
- Estatísticas aprendidas ficam em memória entre rounds; arquivos são carregados no primeiro round, evitando substituir a memória completa por uma cópia limitada gravada em disco.
- Memória em RAM limitada a 12 perfis por módulo. Salvamento compactado respeita orçamento por arquivo e cota disponível.
- Colisões com paredes são consideradas na simulação dos caminhos; há direção de recuperação para o centro quando o ajuste de parede falha.
- Robô se aproxima mais de um alvo sem energia quando não há ondas ameaçadoras próximas.
- Detecção de tiros desconta o ganho de energia do inimigo ao acertar o robô, além do dano causado pelos nossos tiros.
- Salvamento de aprendizado também usa o evento de fim do round, com proteção contra gravação duplicada.
- Validação adicional para contadores inválidos e valores não finitos; falhas ao salvar estatísticas são informadas.

## Observações

As alterações de combate são heurísticas e precisam de comparação em batalhas reais. Não há novo sistema KNN/rede neural: os módulos com esses nomes no ZIP original não participavam da execução. Não foram removidos dados de aprendizado: não havia arquivos `.dat` no material recebido.
