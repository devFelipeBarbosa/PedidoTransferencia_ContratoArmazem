# Geração Automática de Contrato de Armazém + Pedido de Compra (Matriz)

Personalização Sankhya ERP que, ao confirmar um **Pedido de Transferência** na Filial,
gera automaticamente:

1. **Contrato de Armazenagem** (`TCSCON` + `TCSPSC`) na Matriz
2. **Pedido de Compra de Comercialização** (`TGFCAB`/`TGFITE`) vinculado ao contrato

A geração roda como **Regra de Negócio (`IRegra` / `RegraNegocioJava`)** acionada na TOP do
pedido de transferência (regra "15-CONTRATO/PEDIDO TRANSFERÊNCIA").

---

## Versão Sankhya

- **Build:** `4.35b491`
- **JAPE:** `4.36b32`
- **Banco:** Oracle
- **App server:** WildFly + tinyejb

---

## Estrutura do Projeto

```
src/main/java/br/com/oasis/transf/
├── rules/
│   └── GerarContratoTransferencia.java      Regra (adapter — ponto de entrada)
├── service/
│   ├── ContratoArmazemService.java          Cria TCSCON + TCSPSC (autonomous TX)
│   └── GerarPedidoService.java              Chama ArmazensGeraisHelper nativo
└── util/
    ├── TLogCatcher.java                     Logger arquivo (padrão Bruna)
    ├── TLogConfiguration.java               ThreadLocal path/fileName
    └── TLogType.java                        Enum INFO/ERROR

build.gradle                                  Java 8, encoding UTF-8
libs/                                         JARs Sankhya (gitignored)
```

---

## Pré-condições para a regra disparar

Campos no `TGFCAB` do pedido de transferência (Filial):

| Campo                  | Tipo         | Função                                         |
|------------------------|--------------|------------------------------------------------|
| `CODEMP`               | NUMBER       | Deve ser **4** (Filial)                        |
| `AD_GERCONTRTRANSF`    | VARCHAR(1)   | Flag liga/desliga (`'S'` = gera)               |
| `AD_NUMCONTRATO_TRANSF`| NUMBER       | Vazio (preenchido após geração; protege duplicata) |
| `AD_CODSAF`            | NUMBER       | Código da Safra                                |
| `AD_UNICONVSC`         | NUMBER       | Unidade de Conversão SC                        |
| `AD_CODTIPVENDA_CT`    | NUMBER       | Tipo de Venda do Contrato                      |
| `AD_TIPOCONTRATO`      | VARCHAR      | Tipo (Exclusivo / Não Exclusivo)               |
| `AD_TIPCON`            | VARCHAR(1)   | **'V'**=Venda Fixada, **'C'**=Compra Fixada, **'F'**=Compra a Fixar, **'B'**=Bolsa |
| `AD_QTDNEG_SC`         | NUMBER       | Quantidade Negociada                           |
| `AD_VALNEGSC`          | NUMBER       | Valor Negociado por SC                         |
| `AD_DTINIENTREGA`      | DATE         | Data Início Entrega                            |
| `AD_DTTERMINO`         | DATE         | Data Término                                   |
| `AD_PERCTOLEXCED`      | NUMBER       | % Tolerância Excedente                         |
| `AD_TIPOTITULO_CT`     | NUMBER       | Tipo de Título                                 |

---

## Fluxo de Execução

```
Confirmação do Pedido (Central)
        │
        ▼
CACSPBean.confirmarNota → RegraDinamicaHelper.executarRegrasDaTop
        │
        ▼
GerarContratoTransferencia.executa  ← AQUI
        │
        ├─ lerCabecalho(NUNOTA)            SELECT TGFCAB com 16 campos AD_*
        ├─ valida CODEMP=4
        ├─ valida AD_GERCONTRTRANSF='S'
        ├─ valida não há contrato gerado
        │
        ├─ mapearItensComComposicao()      1 JOIN único (sem N+1)
        │     SELECT TGFITE × TPRLMP × TPRATV × TPRPRC (processo 40, versão mais recente)
        │
        ├─ ContratoArmazemService.criar()
        │     ├─ reservarSequencia()       SELECT+UPDATE TGFNUM (ARQUIVO='TCSCON')
        │     └─ PRAGMA AUTONOMOUS_TRANSACTION:
        │           INSERT TCSCON          (NUMCONTRATO ... TIPCON ...)
        │           INSERT TCSPSC          (produto PA do contrato)
        │           COMMIT                 (visibilidade pro próximo passo)
        │
        ├─ GerarPedidoService.gerar(NUMCONTRATO, TIPCON)
        │     └─ ArmazensGeraisHelper.criarNotaComercializacao(jdbc, dwf, num, params, null, cotacao)
        │           └─ Cadeia nativa: cria TGFCAB+TGFITE Matriz (TOP=3030100, LOCAL=3006)
        │
        └─ atualizarNumContrato()          UPDATE TGFCAB.AD_NUMCONTRATO_TRANSF
```

---

## Decisões Técnicas

### 1. Sem REST/HTTP

**Antes:** `HttpUtil.post()` em `http://127.0.0.1:8080/armazem/service.sbr?serviceName=ContratosArmazemGeralSP.gerarPedidoComercializacao`

**Problemas:**
- 11.45s no "Tempo de Código Personalizado" (84.5% do tempo total) — aviso de performance da plataforma
- Stack: socket → filter chain → auth → JSON parse → SP → return → JSON serialize → parse local
- Dependência de `mgeSession` capturada via reflexão em `ThreadLocalWrapper`
- Dependência de porta do WildFly (resolvida via JMX MBean)

**Agora:** chamada direta ao mesmo método interno que o SP invoca:
```java
ArmazensGeraisHelper.criarNotaComercializacao(jdbc, dwf, numContrato, params, null, cotacao)
```

Descoberto por bytecode inspection de `ContratosArmazemGeralSPBean.gerarPedidoComercializacao`
(linhas 481-491 do `.class`).

### 2. AUTONOMOUS_TRANSACTION para TCSCON

Garante que o INSERT do contrato é **commitado antes** da chamada que gera o pedido,
evitando "contrato não encontrado" se a chamada nativa abrir nova TX. PL/SQL block:

```sql
DECLARE PRAGMA AUTONOMOUS_TRANSACTION; BEGIN
  INSERT INTO TCSCON (...) VALUES (...);
  INSERT INTO TCSPSC (...) VALUES (...);
  COMMIT;
END;
```

### 3. NUMCONTRATO via TGFNUM

Reserva sequência igual o ERP nativo:
```sql
SELECT NVL(ULTCOD,0)+1 FROM TGFNUM WHERE ARQUIVO='TCSCON' AND CODEMP=1 AND SERIE='.'
UPDATE TGFNUM SET ULTCOD=... WHERE ARQUIVO='TCSCON' AND CODEMP=1 AND SERIE='.'
```

### 4. Mapeamento de Composição (processo 40)

Cada `CODPROD` do pedido (matéria-prima) precisa de um `CODPRODPA` (produto acabado)
mapeado no **Processo 40 — Beneficiamento Filial**.

**Versão única** (eliminou N+1):
```sql
SELECT ITE.CODPROD CODPRODMP, COMP.CODPRODPA
  FROM TGFITE ITE
  LEFT JOIN (
    SELECT MP.CODPRODMP, MP.CODPRODPA,
           ROW_NUMBER() OVER (PARTITION BY MP.CODPRODMP ORDER BY PRC.VERSAO DESC) RN
      FROM TPRLMP MP
      JOIN TPRATV ATV ON ATV.IDEFX = MP.IDEFX
      JOIN TPRPRC PRC ON PRC.IDPROC = ATV.IDPROC
     WHERE PRC.CODPRC = 40
  ) COMP ON COMP.CODPRODMP = ITE.CODPROD AND COMP.RN = 1
 WHERE ITE.NUNOTA = {NUNOTA}
```

### 5. Cotação para TIPCON='B' (Bolsa)

Quando o contrato é cotado em moeda alternativa, replica query nativa de TSICOT:
```sql
SELECT TSICOT.COTACAO FROM TSICOT
 WHERE TSICOT.CODMOEDA = (SELECT CON1.PPAUTASC FROM TCSCON CON1 WHERE CON1.NUMCONTRATO = ?)
   AND TSICOT.DTMOV = (SELECT MAX(DTMOV) FROM TSICOT WHERE CODMOEDA = ...)
```

Para TIPCON='V'/'C'/'F' → cotação = 0.

### 6. Logging — padrão `TLogCatcher`

`TLogConfiguration` armazena path/fileName em `ThreadLocal` (thread-safe entre regras paralelas).
`TLogCatcher.logInfo/logError` grava em `<repositorio>/personalizacao/log<YYYY-MM-DD>-<nome>.txt`.

- `executa()` configura ThreadLocal, faz `try/catch/logError/throw`, `finally { clear() }`
- Cada método com try/catch captura erros pontuais (lerCabecalho, mapearItens, etc.)
- Diretório criado via `mkdirs()` no primeiro write

### 7. Encoding UTF-8

`build.gradle`:
```gradle
compileJava.options.encoding = 'UTF-8'
```

Default do Gradle no Windows era `windows-1252` → caracteres acentuados ("Armazém", "já")
viravam mojibake ("ArmazÃ©m", "jÃ¡") nas mensagens da plataforma.

---

## Constantes da Personalização

| Constante               | Valor       | Onde                          |
|-------------------------|-------------|-------------------------------|
| CODEMP Matriz contrato  | `1`         | `ContratoArmazemService`      |
| CODPARC Matriz contrato | `4`         | `ContratoArmazemService`      |
| PADCLASS                | `88`        | `ContratoArmazemService`      |
| TIPOARM                 | `'A'`       | `ContratoArmazemService`      |
| TIPO contrato           | `'M'`       | `ContratoArmazemService`      |
| MODALIDADE              | `'C'`       | `ContratoArmazemService`      |
| COBPROPORCAR            | `'E'`       | `ContratoArmazemService`      |
| CIF_FOB                 | `'F'`       | `ContratoArmazemService`      |
| EXIGEPEDIDOPES          | `'S'`       | `ContratoArmazemService`      |
| CODTIPOPER pedido       | `3006`      | `GerarPedidoService`          |
| CODLOCAL pedido         | `3030100`   | `GerarPedidoService`          |
| CODEMP Filial (dispara) | `4`         | `GerarContratoTransferencia`  |
| CODPRC composição       | `40`        | `GerarContratoTransferencia`  |

---

## Build & Deploy

```bash
./gradlew jar
# Gera: build/libs/oasis-transf-contrato-1.0.0.jar
```

Copiar JAR para a pasta de personalizações do Sankhya (ex: `<sankhya>/personalizacao/`)
e fazer hot reload do módulo.

---

## Troubleshooting

Logs ficam em `<repositorio-sankhya>/personalizacao/log<YYYY-MM-DD>-GerarContratoTransferencia.txt`.

Padrão sucesso esperado:
```
[INFO] Iniciando regra para NUNOTA=83985
[INFO] Cabecalho lido. AD_GERCONTRTRANSF=S CODEMP=4 AD_TIPCON=C ...
[INFO] Contrato 305 criado para NUNOTA=83985. Gerando pedido via ArmazensGeraisHelper nativo...
[INFO] Chamando ArmazensGeraisHelper.criarNotaComercializacao NUMCONTRATO=305 TIPCON=C COTACAO=0
[INFO] Pedido de compra NUNOTA=83986 gerado. Contrato=305
```

Erros frequentes e causas:

| Mensagem                                                          | Causa                                                            |
|-------------------------------------------------------------------|------------------------------------------------------------------|
| `Falha ao converter para representação interna`                   | `getBigDecimal` em coluna texto (ex: `AD_TIPCON` é CHAR)         |
| `Composição não encontrada no processo 40`                        | Produto sem mapeamento em TPRLMP/TPRATV/TPRPRC com CODPRC=40     |
| `Contrato de Armazém nº X já gerado para este pedido`             | `AD_NUMCONTRATO_TRANSF` já preenchido — proteção contra duplicata|
| `Geração ... permitida apenas para CODEMP=4 (Filial)`             | Tentativa de disparar em empresa errada                          |

---

## Tabelas Envolvidas

| Tabela     | Papel                                              |
|------------|----------------------------------------------------|
| `TGFCAB`   | Pedido de transferência (Filial) + pedido matriz   |
| `TGFITE`   | Itens dos pedidos                                  |
| `TCSCON`   | Contrato de Armazenagem (criado)                   |
| `TCSPSC`   | Produtos/Serviços do contrato (criado)             |
| `TGFNUM`   | Numerador (NUMCONTRATO sequência)                  |
| `TPRLMP`   | Lista de matérias-primas das fórmulas              |
| `TPRATV`   | Atividades do processo                             |
| `TPRPRC`   | Processos (versão mais recente)                    |
| `TSICOT`   | Cotações (usado se TIPCON='B')                     |

---

## JARs do Classpath (compileOnly)

- `SankhyaW-extensions.jar` — `RegraNegocioJava`, `ContextoRegra`, `QueryExecutor`
- `jape-4.36b32.jar` — `EntityFacade`, `JdbcWrapper`, `NativeSql`
- `mge-modelcore-4.35b491.jar` — `EntityFacadeFactory`, `SWRepositoryUtils`
- `mgearmazem-model-4.35b491.jar` — `ArmazensGeraisHelper.criarNotaComercializacao`

---

## Autoria

Felipe Barbosa — Oasis Alimentos
