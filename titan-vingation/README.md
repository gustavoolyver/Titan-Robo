# Titan Vingation

Robô para **Robocode clássico**, com fontes organizados, compilação compatível com Java 8 e o JAR pronto em `dist/titan-vingation.jar`.

## Usar o robô pronto

1. No Robocode, use **Robot → Import robot** e selecione `dist/titan-vingation.jar`. Alternativamente, copie esse JAR para a pasta `robots` da instalação.
2. Atualize a lista de robôs e selecione **titan.TitanVingation 1.0**.
3. Comece com batalhas 1 contra 1. O movimento por ondas foi desenvolvido para duelos; batalhas com vários inimigos usam varredura contínua do radar, mas não têm uma estratégia completa de movimento melee.

## Compilar no GitHub Codespaces

Extraia o ZIP e abra o terminal na pasta que contém este README:

```bash
bash scripts/build.sh
```

Resultado: `dist/titan-vingation.jar`.

Para executar as verificações de regressão:

```bash
bash scripts/test.sh
```

Requer um **JDK 8 ou superior**. Não depende de Maven, `rg` ou downloads durante a compilação. A dependência já fornecida no ZIP original foi preservada em `lib/robocode-api.jar`; ela é usada para compilar, não é incluída dentro do JAR do robô. Os scripts também funcionam em ambientes que disponibilizam as ferramentas do JDK por módulos Java, sem executáveis `javac` e `jar` no PATH. Execute os scripts pelo Bash no Linux/Codespaces.

## Estrutura

- `src/main/java/titan/TitanVingation.java`: coordena os eventos e módulos.
- `src/main/java/titan/gun/`: mira linear, circular e GuessFactor, comparação de tiros virtuais, previsão de movimento e controle dos disparos.
- `src/main/java/titan/move/`: movimento por wave surfing, com duas ondas e perfis de perigo.
- `src/main/java/titan/radar/`: busca, travamento em duelos e varredura em batalhas múltiplas.
- `src/main/java/titan/stats/`: persistência compactada do aprendizado e contagem dos tiros reais.
- `src/main/java/titan/utils/`: estado dos adversários, limites do campo e geometria.
- `src/main/resources/titan/`: identificação do robô para o Robocode.
- `src/test/java/`: testes locais com verificações reais, separados do JAR.
- `docs/`: relatório da revisão, relação completa dos arquivos removidos/renomeados e resultados da validação.
- `dist/`: JAR pronto para importar.

## Aprendizado e transferência para outro computador

O aprendizado muda **dados estatísticos**, não reescreve o código Java. O JAR não recebe automaticamente o aprendizado de cada batalha.

Ao terminar cada round, o robô tenta salvar na pasta de dados que o próprio Robocode fornece para `titan.TitanVingation`:

| Arquivo | Conteúdo |
| --- | --- |
| `GuessFactorTargeting.dat` | Distribuição dos movimentos de fuga dos adversários. |
| `VirtualGuns.dat` | Desempenho das três miras virtuais. |
| `SurfMovement.dat` | Perfis de acertos recebidos e passagens pelas ondas. |
| `stats.dat` | Contagem de tiros reais disparados e acertados. |

**Esses nomes foram preservados para permitir migração dos dados antigos.** Antes de trocar a versão, faça uma cópia da pasta de dados anterior (`TitanV3` ou `TitanVingation`). Com o Robocode fechado, copie os quatro arquivos para a pasta de dados atribuída à nova classe. Se a pasta ainda não existir, rode uma batalha curta para o Robocode criá-la; feche o programa e então substitua os arquivos. O nome/local exato da pasta depende da instalação e da versão importada; procure a pasta de dados correspondente ao robô dentro de `robots`.

Para continuar em outro computador, transfira **o JAR e os quatro arquivos de dados**. Este ZIP não contém aprendizado de batalhas anteriores: o arquivo enviado não trazia arquivos `.dat`.

Há limite de **12 adversários por módulo**, priorizando os usados mais recentemente, e orçamento de até **18.000 bytes por arquivo de aprendizado**. Se necessário, o salvamento remove perfis antigos da cópia gravada para caber na cota disponível. Portanto, o histórico é limitado. Arquivos incompatíveis ou corrompidos são ignorados com aviso no console. Falhas de escrita também são informadas; confira o console e as datas dos arquivos depois das batalhas.

## Limites da validação

A compilação Java 8, o conteúdo do JAR e 26 verificações automatizadas foram validados nesta entrega. Os testes usam um robô simulado para verificar lógica e leitura/serialização dos dados. **Não foram executadas batalhas no motor completo do Robocode nem validada a escrita sob o sandbox real do jogo.** A API fornecida no ZIP não inclui sozinha uma instalação completa do motor. Não há medição que comprove aumento da taxa de vitórias.

Para avaliar resultados, use os mesmos adversários, campo e quantidade de rounds; compare séries com dados novos e séries com dados acumulados, guardando os resultados e os avisos do console. A adaptação estatística não garante melhoria contínua contra qualquer adversário.
