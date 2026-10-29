# Validação da entrega

- Compilação: JDK 17 com `--release 8`, UTF-8 e `-Xlint:all`; sem erros ou avisos.
- Regressão: 26 verificações passaram com robô simulado.
- Casos cobertos: limites do campo, geometria, variação angular, energia, previsão de mira, modos do radar, potência de finalização, precisão para disparar, alvos antigos, seleção de mira virtual, treinamento GuessFactor, limite de perfis, ida e volta da memória compactada, dimensões incompatíveis, checksum inválido, ausência de arquivo, valores não finitos e comandos de movimento.
- JAR: classe principal e metadados presentes, bytecode Java 8 em todas as classes; sem testes ou dependência Robocode embutidos.
- Escrita no sandbox real e batalhas: não executadas. Os testes de persistência validam serialização e leitura local; não simulam permissões/cota do motor real.
- Avisos de formato inválido e checksum corrompido durante os testes são esperados: fazem parte dos casos de rejeição de dados danificados.

Execute `bash scripts/test.sh` para reproduzir a compilação e as 26 verificações.
