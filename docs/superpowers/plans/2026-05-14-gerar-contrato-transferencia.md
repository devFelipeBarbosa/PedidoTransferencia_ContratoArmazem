# Gerar Contrato Transferência — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ao confirmar Pedido de Compra na Filial (CODEMP=4, TOP=1307) com flag ativa, gerar automaticamente um Contrato de Armazém (TCSCON) e o Pedido de Compra na Matriz (CODEMP=1, TOP=3006) via Regra de Negócio Java.

**Architecture:** `RegraNegocioJava` dispara no evento Confirmar do `CabecalhoNota`, valida composição produtiva via `TPRLMP/TPRATV/TPRPRC` (processo 40), cria `TCSCON`+`TCSPSC` via DynamicVO JAPE, e invoca o service nativo `ContratosArmazemGeralSP.gerarPedidoComercializacao` via HTTP interno reusando a `mgeSession` do usuário para garantir vínculos nativos do módulo Armazém.

**Tech Stack:** Java 8, Sankhya JAPE (JapeSession, DynamicVO, NativeSql), `RegraNegocioJava` (interface Central), Gradle, WildFly (deploy em `customizacoes/`), Oracle DB.

---

## Referências críticas

| Item | Valor |
|---|---|
| Pedido Filial | CODEMP=4, TOP=1307 |
| Pedido Matriz | CODEMP=1, TOP=3006 |
| Parceiro contrato | CODPARC=4 (OASIS ALIMENTOS LTDA) |
| Processo composição | CODPRC=40 (Beneficiamento Filial) |
| Local armazém | codLocal=3030100 |
| Service Armazém | `ContratosArmazemGeralSP.gerarPedidoComercializacao` |
| URI | `/armazem/service.sbr` |
| Resposta NUNOTA | `responseBody.mensagem.$` (ex: `"83977,"`) |
| Modelo base | `github.com/devFelipeBarbosa/CadastroProdutoSimplificado` |

---

## File Structure

```
src/main/java/br/com/oasis/transf/
├── rules/
│   └── GerarContratoTransferencia.java   ← RegraNegocioJava — orquestra o fluxo
├── service/
│   ├── ContratoArmazemService.java       ← INSERT TCSCON + TCSPSC via DynamicVO
│   └── GerarPedidoService.java           ← HTTP POST /armazem/service.sbr
└── util/
    ├── ComposicaoUtil.java               ← query TPRLMP→CODPRODPA (processo 40)
    └── HttpUtil.java                     ← POST helper com mgeSession

build.gradle                              ← dependências Sankhya
```

---

## Pré-requisitos Sankhya (fazer antes de codificar)

### Campos Adicionais em TGFCAB

Criar via **Central → Configurações → Campos Adicionais** na entidade `CabecalhoNota`:

| Campo | Tipo | Label na tela |
|---|---|---|
| `AD_GERCONTRTRANSF` | Checkbox (S/N) | Gerar Contrato Transferência |
| `AD_CODSAF` | Inteiro | Safra |
| `AD_UNICONVSC` | Decimal | Unid. Conversão (SC) |
| `AD_CODTIPVENDA_CT` | Inteiro | Tipo de Negociação |
| `AD_TIPOCONTRATO` | Texto (1) | Tipo de Contrato |
| `AD_QTDNEG_SC` | Decimal | Quantidade (SC) |
| `AD_VALNEGSC` | Decimal | Valor Negociado (SC) |
| `AD_DTINIENTREGA` | Data | Data Início Entrega |
| `AD_DTTERMINO` | Data | Data Término |
| `AD_PERCTOLEXCED` | Decimal | % Tolerância |
| `AD_TIPOTITULO_CT` | Inteiro | Tipo de Título |
| `AD_NUMCONTRATO_TRANSF` | Inteiro | Contrato Gerado (somente leitura) |

Criar aba **"Contrato Transferência"** no layout do Pedido de Compra e adicionar esses campos.

---

## Task 1: Setup do Projeto Gradle

**Files:**
- Create: `build.gradle`
- Create: `src/main/java/br/com/oasis/transf/rules/.gitkeep`

- [ ] **Step 1: Clonar estrutura base do repositório modelo**

```bash
git clone https://github.com/devFelipeBarbosa/CadastroProdutoSimplificado.git _modelo
cp _modelo/build.gradle .
cp _modelo/settings.gradle .
rm -rf _modelo
```

- [ ] **Step 2: Ajustar `build.gradle` para o novo projeto**

```groovy
plugins {
    id 'java'
}

group = 'br.com.oasis.transf'
version = '1.0.0'

repositories {
    flatDir { dirs 'libs' }
}

dependencies {
    compileOnly fileTree(dir: 'libs', include: ['*.jar'])
}

jar {
    archiveFileName = 'oasis-transf-contrato-${version}.jar'
    from sourceSets.main.output
}
```

- [ ] **Step 3: Copiar JARs do Sankhya para `libs/`**

Copiar de `{SANKHYA_HOME}/server/customizacoes/` os JARs:
- `mgecore.jar`
- `mgeframework.jar`
- `jape.jar`
- `sankhyautil.jar`

```bash
mkdir libs
# Copiar JARs conforme acima
```

- [ ] **Step 4: Criar estrutura de pacotes**

```bash
mkdir -p src/main/java/br/com/oasis/transf/rules
mkdir -p src/main/java/br/com/oasis/transf/service
mkdir -p src/main/java/br/com/oasis/transf/util
```

- [ ] **Step 5: Verificar build limpo**

```bash
./gradlew clean build
```
Expected: `BUILD SUCCESSFUL` (sem classes Java ainda, só estrutura)

- [ ] **Step 6: Commit**

```bash
git add build.gradle settings.gradle src/ libs/
git commit -m "feat: setup projeto Gradle oasis-transf-contrato"
```

---

## Task 2: ComposicaoUtil — Mapeamento CODPRODMP → CODPRODPA

**Files:**
- Create: `src/main/java/br/com/oasis/transf/util/ComposicaoUtil.java`

**Contexto:** Dado `CODPRODMP` (item digitado no Pedido da Filial), retorna `CODPRODPA` (item que vai no Contrato de Armazém) consultando o processo 40 (Beneficiamento Filial) na versão mais recente. Retorna `null` se não encontrar — a regra usa isso para bloquear o pedido.

- [ ] **Step 1: Criar `ComposicaoUtil.java`**

```java
package br.com.oasis.transf.util;

import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import java.math.BigDecimal;

public class ComposicaoUtil {

    private ComposicaoUtil() {}

    /**
     * Busca CODPRODPA para o CODPRODMP informado,
     * usando processo 40 (Beneficiamento Filial), versão mais recente.
     * Retorna null se não encontrar composição.
     */
    public static BigDecimal buscarCodProdPA(QueryExecutor q, BigDecimal codProdMP) throws Exception {
        try {
            q.setParam("CODPRODMP", codProdMP);
            q.nativeSelect(
                "SELECT CODPRODPA" +
                " FROM (" +
                "   SELECT MP.CODPRODPA," +
                "     ROW_NUMBER() OVER (PARTITION BY MP.CODPRODPA ORDER BY PRC.VERSAO DESC) AS RN" +
                "   FROM TPRLMP MP" +
                "   INNER JOIN TPRATV ATV ON ATV.IDEFX = MP.IDEFX" +
                "   INNER JOIN TPRPRC PRC ON PRC.IDPROC = ATV.IDPROC" +
                "   WHERE MP.CODPRODMP = {CODPRODMP}" +
                "     AND PRC.CODPRC = 40" +
                " )" +
                " WHERE RN = 1 AND ROWNUM = 1"
            );
            if (q.next()) {
                return q.getBigDecimal("CODPRODPA");
            }
            return null;
        } finally {
            q.close();
        }
    }
}
```

- [ ] **Step 2: Compilar**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL` — sem erros de compilação.

- [ ] **Step 3: Testar manualmente no ERP**

Executar SQL no SGE/Monitor para validar a query retorna `16138` para `CODPRODMP=2`:

```sql
SELECT CODPRODPA
FROM (
    SELECT MP.CODPRODPA,
        ROW_NUMBER() OVER (PARTITION BY MP.CODPRODPA ORDER BY PRC.VERSAO DESC) AS RN
    FROM TPRLMP MP
    INNER JOIN TPRATV ATV ON ATV.IDEFX = MP.IDEFX
    INNER JOIN TPRPRC PRC ON PRC.IDPROC = ATV.IDPROC
    WHERE MP.CODPRODMP = 2
      AND PRC.CODPRC = 40
)
WHERE RN = 1 AND ROWNUM = 1
```

Expected: `CODPRODPA = 16138`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/oasis/transf/util/ComposicaoUtil.java
git commit -m "feat: ComposicaoUtil busca CODPRODPA via processo 40 versao mais recente"
```

---

## Task 3: HttpUtil — POST helper com mgeSession

**Files:**
- Create: `src/main/java/br/com/oasis/transf/util/HttpUtil.java`

**Contexto:** Faz HTTP POST para o `/armazem/service.sbr` reusando a `mgeSession` do usuário logado. Necessário porque o service `gerarPedidoComercializacao` roda num WAR separado (Armazém), inacessível via JAPE direto.

- [ ] **Step 1: Criar `HttpUtil.java`**

```java
package br.com.oasis.transf.util;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpUtil {

    private HttpUtil() {}

    /**
     * Executa HTTP POST com body JSON.
     * @param url    URL completa incluindo query params
     * @param body   JSON string do body
     * @return       Response body como String
     */
    public static String post(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");

            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(input.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }

            int status = conn.getResponseCode();
            InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }
}
```

- [ ] **Step 2: Compilar**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/br/com/oasis/transf/util/HttpUtil.java
git commit -m "feat: HttpUtil para POST interno ao servico do modulo Armazem"
```

---

## Task 4: GerarPedidoService — Invoca `gerarPedidoComercializacao`

**Files:**
- Create: `src/main/java/br/com/oasis/transf/service/GerarPedidoService.java`

**Contexto:** Monta o payload JSON exato capturado no HAR e chama `/armazem/service.sbr`. O payload contém o TCSCON completo em `requestBody.contratos.loadRecordsRequest`. A resposta contém o(s) NUNOTA gerado(s) em `responseBody.mensagem.$` (ex: `"83977,"`).

- [ ] **Step 1: Criar `GerarPedidoService.java`**

```java
package br.com.oasis.transf.service;

import br.com.oasis.transf.util.HttpUtil;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class GerarPedidoService {

    private static final int COD_TIP_OPER = 3006;
    private static final int COD_LOCAL    = 3030100;

    private GerarPedidoService() {}

    /**
     * Invoca ContratosArmazemGeralSP.gerarPedidoComercializacao.
     *
     * @param host       ex: "http://localhost:8261"
     * @param mgeSession token de sessão do usuário logado
     * @param tcscon     Map com todos os campos do TCSCON criado (chave = nome do campo)
     * @return           NUNOTA do Pedido de Compra gerado na Matriz
     */
    public static BigDecimal gerar(String host, String mgeSession, Map<String, Object> tcscon) throws Exception {
        String url = host + "/armazem/service.sbr"
            + "?serviceName=ContratosArmazemGeralSP.gerarPedidoComercializacao"
            + "&outputType=json"
            + "&application=ContratosArmazemGeral"
            + "&resourceID=br.com.sankhya.armazem.cad.cont.armazem"
            + "&mgeSession=" + mgeSession;

        String dtEmissao = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
        String body = buildPayload(tcscon, dtEmissao);

        String response = HttpUtil.post(url, body);
        return parseNunota(response);
    }

    /**
     * Monta JSON conforme payload capturado no HAR.
     * loadRecordsRequest contém os campos do TCSCON.
     */
    static String buildPayload(Map<String, Object> tcscon, String dtEmissao) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"serviceName\":\"ContratosArmazemGeralSP.gerarPedidoComercializacao\",");
        sb.append("\"requestBody\":{");
        sb.append("\"contratos\":{\"loadRecordsRequest\":[{");

        boolean first = true;
        for (Map.Entry<String, Object> e : tcscon.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("\"\"");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append("\"").append(String.valueOf(v).replace("\"", "\\\"")).append("\"");
            }
            first = false;
        }

        sb.append("}]},");
        sb.append("\"codTipOper\":").append(COD_TIP_OPER).append(",");
        sb.append("\"dtEmissao\":\"").append(dtEmissao).append("\",");
        sb.append("\"codLocal\":").append(COD_LOCAL).append(",");
        sb.append("\"controle\":\" \"}}");
        return sb.toString();
    }

    /**
     * Extrai NUNOTA do response.
     * Response: {"responseBody":{"mensagem":{"$":"83977,"}}}
     * O campo "mensagem.$" pode conter múltiplos NUNOTAs separados por vírgula.
     * Retorna o primeiro.
     */
    static BigDecimal parseNunota(String response) throws Exception {
        // Parse manual — sem dependência de lib JSON
        String marker = "\"mensagem\":{\"$\":\"";
        int idx = response.indexOf(marker);
        if (idx < 0) throw new Exception("Resposta inesperada do service Armazem: " + response);
        int start = idx + marker.length();
        int end = response.indexOf("\"", start);
        String raw = response.substring(start, end).replace(",", "").trim();
        if (raw.isEmpty()) throw new Exception("NUNOTA nao encontrado na resposta: " + response);
        return new BigDecimal(raw.split(",")[0].trim());
    }
}
```

- [ ] **Step 2: Compilar**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Validar `parseNunota` manualmente**

Simular response do HAR no console ou teste local:

```java
// Response real capturado no HAR:
String resp = "{\"serviceName\":\"ContratosArmazemGeralSP.gerarPedidoComercializacao\","
            + "\"status\":\"1\",\"pendingPrinting\":\"false\","
            + "\"responseBody\":{\"mensagem\":{\"$\":\"83977,\"}}}";
BigDecimal nunota = GerarPedidoService.parseNunota(resp);
// Expected: 83977
System.out.println(nunota); // → 83977
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/oasis/transf/service/GerarPedidoService.java
git commit -m "feat: GerarPedidoService invoca gerarPedidoComercializacao via HTTP interno"
```

---

## Task 5: ContratoArmazemService — INSERT TCSCON + TCSPSC

**Files:**
- Create: `src/main/java/br/com/oasis/transf/service/ContratoArmazemService.java`

**Contexto:** Cria o Contrato de Armazém na Matriz (CODEMP=1) via DynamicVO JAPE. Campos fixos do negócio mais campos vindos dos `AD_*` do Pedido da Filial. Após INSERT TCSCON, faz INSERT TCSPSC com o `CODPRODPA` mapeado. Retorna `NUMCONTRATO` gerado.

- [ ] **Step 1: Criar `ContratoArmazemService.java`**

```java
package br.com.oasis.transf.service;

import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.dao.EntityDAO;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ContratoArmazemService {

    // Valores fixos do negócio (confirmados no INSERT capturado no monitor)
    private static final int    CODEMP          = 1;
    private static final int    CODPARC         = 4;
    private static final String ATIVO           = "S";
    private static final String CIF_FOB         = "F";
    private static final int    PADCLASS        = 88;
    private static final int    CODMOEDA        = 0;
    private static final int    CODCONTATO      = 1;
    private static final String EXIGEPEDIDOPES  = "S";
    private static final String MODALIDADE      = "C";
    private static final String TIPOARM         = "A";
    private static final String TIPO            = "M";
    private static final String COBPROPORCAR    = "E";
    private static final String CIF_FOB_VAL     = "F";

    private ContratoArmazemService() {}

    /**
     * Cria TCSCON + TCSPSC na Matriz (CODEMP=1).
     *
     * @param session    JapeSession aberta no contexto da regra
     * @param pedido     Campos do Pedido da Filial (AD_* + CODNAT + CODCENCUS)
     * @param codProdPA  CODPRODPA mapeado via ComposicaoUtil
     * @return           Map com NUMCONTRATO e todos os campos do TCSCON criado
     *                   (usado como payload para GerarPedidoService)
     */
    public static Map<String, Object> criar(JapeSession.SessionHandle session,
                                             Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {

        BigDecimal numContrato = inserirTcscon(session, pedido);
        inserirTcspsc(session, numContrato, codProdPA);

        // Montar Map com dados do TCSCON para o payload HTTP
        Map<String, Object> tcsconData = buildTcsconMap(numContrato, pedido, codProdPA);
        return tcsconData;
    }

    private static BigDecimal inserirTcscon(JapeSession.SessionHandle session,
                                             Map<String, Object> pedido) throws Exception {
        EntityDAO dao = EntityDAO.getInstance(session);
        DynamicVO vo = new DynamicVO("TCSCON");

        // Campos fixos
        vo.setField("CODEMP",         new BigDecimal(CODEMP));
        vo.setField("CODPARC",        new BigDecimal(CODPARC));
        vo.setField("ATIVO",          ATIVO);
        vo.setField("CIF_FOB",        CIF_FOB);
        vo.setField("PADCLASS",       new BigDecimal(PADCLASS));
        vo.setField("CODMOEDA",       new BigDecimal(CODMOEDA));
        vo.setField("CODCONTATO",     new BigDecimal(CODCONTATO));
        vo.setField("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
        vo.setField("MODALIDADE",     MODALIDADE);
        vo.setField("TIPOARM",        TIPOARM);
        vo.setField("TIPO",           TIPO);
        vo.setField("COBPROPORCAR",   COBPROPORCAR);
        vo.setField("SITCONT",        "A");
        vo.setField("DTCONTRATO",     new Timestamp(new Date().getTime()));

        // Campos vindos do Pedido da Filial (AD_*)
        vo.setField("CODSAF",        toBD(pedido.get("AD_CODSAF")));
        vo.setField("UNICONVSC",     toBD(pedido.get("AD_UNICONVSC")));
        vo.setField("CODTIPVENDA",   toBD(pedido.get("AD_CODTIPVENDA_CT")));
        vo.setField("TIPOCONTRATO",  toStr(pedido.get("AD_TIPOCONTRATO")));
        vo.setField("QTDNEG",        toBD(pedido.get("AD_QTDNEG_SC")));
        vo.setField("VALNEGSC",      toBD(pedido.get("AD_VALNEGSC")));
        vo.setField("DTINIENTREGA",  toTs(pedido.get("AD_DTINIENTREGA")));
        vo.setField("DTTERMINO",     toTs(pedido.get("AD_DTTERMINO")));
        vo.setField("PERCTOLEXCED",  toBD(pedido.get("AD_PERCTOLEXCED")));
        vo.setField("TIPOTITULO",    toBD(pedido.get("AD_TIPOTITULO_CT")));

        // Campos herdados do cabeçalho do pedido
        vo.setField("CODNAT",        toBD(pedido.get("CODNAT")));
        vo.setField("CODCENCUS",     toBD(pedido.get("CODCENCUS")));

        EntityVO saved = dao.persist(vo);
        return (BigDecimal) saved.getFieldValue("NUMCONTRATO");
    }

    private static void inserirTcspsc(JapeSession.SessionHandle session,
                                       BigDecimal numContrato,
                                       BigDecimal codProdPA) throws Exception {
        EntityDAO dao = EntityDAO.getInstance(session);
        DynamicVO vo = new DynamicVO("TCSPSC");

        vo.setField("NUMCONTRATO", numContrato);
        vo.setField("CODPROD",     codProdPA);
        vo.setField("SITPROD",     "A");
        vo.setField("IMPRNOTA",    "S");
        vo.setField("IMPROS",      "N");
        vo.setField("LIMITANTE",   "N");
        vo.setField("PRODPRINC",   "N");
        vo.setField("QTDEPREVISTA", BigDecimal.ZERO);
        vo.setField("VLRUNIT",     BigDecimal.ZERO);
        vo.setField("CODUSUALTREG", BigDecimal.ZERO);
        vo.setField("DHALTREG",    new Timestamp(new Date().getTime()));

        dao.persist(vo);
    }

    /**
     * Monta Map com campos necessários para o payload de GerarPedidoService.
     * Baseado no loadRecordsRequest capturado no HAR.
     */
    private static Map<String, Object> buildTcsconMap(BigDecimal numContrato,
                                                       Map<String, Object> pedido,
                                                       BigDecimal codProdPA) {
        Map<String, Object> m = new HashMap<>();
        m.put("NUMCONTRATO",   numContrato);
        m.put("CODPROD",       codProdPA);
        m.put("CODEMP",        CODEMP);
        m.put("CODPARC",       CODPARC);
        m.put("ATIVO",         ATIVO);
        m.put("CIF_FOB",       CIF_FOB);
        m.put("PADCLASS",      PADCLASS);
        m.put("CODMOEDA",      CODMOEDA);
        m.put("CODCONTATO",    CODCONTATO);
        m.put("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
        m.put("MODALIDADE",    MODALIDADE);
        m.put("TIPOARM",       TIPOARM);
        m.put("TIPO",          TIPO);
        m.put("COBPROPORCAR",  COBPROPORCAR);
        m.put("SITCONT",       "A");
        m.put("CODSAF",        pedido.get("AD_CODSAF"));
        m.put("UNICONVSC",     pedido.get("AD_UNICONVSC"));
        m.put("CODTIPVENDA",   pedido.get("AD_CODTIPVENDA_CT"));
        m.put("TIPOCONTRATO",  pedido.get("AD_TIPOCONTRATO"));
        m.put("QTDNEG",        pedido.get("AD_QTDNEG_SC"));
        m.put("VALNEGSC",      pedido.get("AD_VALNEGSC"));
        m.put("DTINIENTREGA",  pedido.get("AD_DTINIENTREGA"));
        m.put("DTTERMINO",     pedido.get("AD_DTTERMINO"));
        m.put("PERCTOLEXCED",  pedido.get("AD_PERCTOLEXCED"));
        m.put("TIPOTITULO",    pedido.get("AD_TIPOTITULO_CT"));
        m.put("CODNAT",        pedido.get("CODNAT"));
        m.put("CODCENCUS",     pedido.get("CODCENCUS"));
        return m;
    }

    // --- helpers de conversão ---

    private static BigDecimal toBD(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        String s = v.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private static Timestamp toTs(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof Date) return new Timestamp(((Date) v).getTime());
        return null;
    }
}
```

- [ ] **Step 2: Compilar**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/br/com/oasis/transf/service/ContratoArmazemService.java
git commit -m "feat: ContratoArmazemService cria TCSCON e TCSPSC via DynamicVO JAPE"
```

---

## Task 6: GerarContratoTransferencia — Regra Principal

**Files:**
- Create: `src/main/java/br/com/oasis/transf/rules/GerarContratoTransferencia.java`

**Contexto:** Implementa `RegraNegocioJava`. Dispara no evento Confirmar de `CabecalhoNota`. Orquestra todo o fluxo: validação → composição → criação TCSCON → geração Pedido → feedback.

**Como obter `mgeSession`:** `SankhyaUtil.getSankhyaParameter("mgeSession")` ou via `contexto.getQuery()` → `JapeSession` → token. Na prática, o método mais simples é `br.com.sankhya.jape.core.JapeSession.getCurrentSessionID()` (disponível no classpath MGE).

- [ ] **Step 1: Criar `GerarContratoTransferencia.java`**

```java
package br.com.oasis.transf.rules;

import br.com.oasis.transf.service.ContratoArmazemService;
import br.com.oasis.transf.service.GerarPedidoService;
import br.com.oasis.transf.util.ComposicaoUtil;
import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.extensions.regrasnegocio.RegraNegocioJava;
import br.com.sankhya.jape.core.JapeSession;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class GerarContratoTransferencia implements RegraNegocioJava {

    // Endereço base do servidor — usar localhost pois roda server-side
    private static final String HOST = "http://localhost:8261";

    @Override
    public void executa(ContextoRegra contexto) throws Exception {

        BigDecimal nuNota = contexto.getNunota();
        if (nuNota == null) return;

        // [1] Ler campos do TGFCAB (flag + campos AD_*)
        Map<String, Object> cabecalho = lerCabecalho(contexto, nuNota);

        // [2] Verificar flag AD_GERCONTRTRANSF
        String flag = (String) cabecalho.get("AD_GERCONTRTRANSF");
        if (!"S".equalsIgnoreCase(flag)) return;

        // [3] Validar empresa = Filial (CODEMP=4)
        BigDecimal codEmp = (BigDecimal) cabecalho.get("CODEMP");
        if (codEmp == null || codEmp.intValue() != 4) {
            contexto.setSucesso(false);
            contexto.mostraErro("Geração de Contrato Transferência permitida apenas para CODEMP=4 (Filial).");
            return;
        }

        // [4] Buscar itens do pedido e mapear composição
        Map<BigDecimal, BigDecimal> itensMapeados = mapearItens(contexto, nuNota);
        if (itensMapeados == null) return; // erro já setado no contexto

        // Usar o primeiro (e principal) CODPRODPA mapeado para o TCSPSC
        BigDecimal codProdPA = itensMapeados.values().iterator().next();

        // [5] Criar TCSCON + TCSPSC via JAPE
        JapeSession.SessionHandle session = JapeSession.open();
        BigDecimal numContrato;
        Map<String, Object> tcsconData;
        try {
            tcsconData = ContratoArmazemService.criar(session, cabecalho, codProdPA);
            numContrato = (BigDecimal) tcsconData.get("NUMCONTRATO");
        } finally {
            JapeSession.close(session);
        }

        // [6] Invocar gerarPedidoComercializacao via HTTP
        String mgeSession = JapeSession.getCurrentSessionID();
        BigDecimal nuNotaMatriz = GerarPedidoService.gerar(HOST, mgeSession, tcsconData);

        // [7] Gravar NUMCONTRATO no campo adicional do pedido
        atualizarNumContrato(contexto, nuNota, numContrato);

        // [8] Feedback para o usuário
        contexto.setMensagem(
            "Contrato de Armazém nº " + numContrato +
            " e Pedido de Compra (Matriz) nº " + nuNotaMatriz +
            " gerados com sucesso."
        );
    }

    /**
     * Lê TGFCAB pelo NUNOTA: campos AD_* + CODEMP + CODNAT + CODCENCUS.
     */
    private Map<String, Object> lerCabecalho(ContextoRegra contexto, BigDecimal nuNota) throws Exception {
        QueryExecutor q = contexto.getQuery();
        Map<String, Object> dados = new HashMap<>();
        try {
            q.setParam("NUNOTA", nuNota);
            q.nativeSelect(
                "SELECT CODEMP, CODNAT, CODCENCUS," +
                "  AD_GERCONTRTRANSF, AD_CODSAF, AD_UNICONVSC, AD_CODTIPVENDA_CT," +
                "  AD_TIPOCONTRATO, AD_QTDNEG_SC, AD_VALNEGSC, AD_DTINIENTREGA," +
                "  AD_DTTERMINO, AD_PERCTOLEXCED, AD_TIPOTITULO_CT" +
                " FROM TGFCAB WHERE NUNOTA = {NUNOTA}"
            );
            if (q.next()) {
                dados.put("CODEMP",              q.getBigDecimal("CODEMP"));
                dados.put("CODNAT",              q.getBigDecimal("CODNAT"));
                dados.put("CODCENCUS",           q.getBigDecimal("CODCENCUS"));
                dados.put("AD_GERCONTRTRANSF",   q.getString("AD_GERCONTRTRANSF"));
                dados.put("AD_CODSAF",           q.getBigDecimal("AD_CODSAF"));
                dados.put("AD_UNICONVSC",        q.getBigDecimal("AD_UNICONVSC"));
                dados.put("AD_CODTIPVENDA_CT",   q.getBigDecimal("AD_CODTIPVENDA_CT"));
                dados.put("AD_TIPOCONTRATO",     q.getString("AD_TIPOCONTRATO"));
                dados.put("AD_QTDNEG_SC",        q.getBigDecimal("AD_QTDNEG_SC"));
                dados.put("AD_VALNEGSC",         q.getBigDecimal("AD_VALNEGSC"));
                dados.put("AD_DTINIENTREGA",     q.getTimestamp("AD_DTINIENTREGA"));
                dados.put("AD_DTTERMINO",        q.getTimestamp("AD_DTTERMINO"));
                dados.put("AD_PERCTOLEXCED",     q.getBigDecimal("AD_PERCTOLEXCED"));
                dados.put("AD_TIPOTITULO_CT",    q.getBigDecimal("AD_TIPOTITULO_CT"));
            }
        } finally {
            q.close();
        }
        return dados;
    }

    /**
     * Lê itens do pedido (TGFITE) e mapeia CODPRODMP → CODPRODPA.
     * Bloqueia e retorna null se algum item não tiver composição no processo 40.
     */
    private Map<BigDecimal, BigDecimal> mapearItens(ContextoRegra contexto,
                                                     BigDecimal nuNota) throws Exception {
        Map<BigDecimal, BigDecimal> mapa = new HashMap<>();

        QueryExecutor qItens = contexto.getQuery();
        try {
            qItens.setParam("NUNOTA", nuNota);
            qItens.nativeSelect("SELECT CODPROD FROM TGFITE WHERE NUNOTA = {NUNOTA}");
            while (qItens.next()) {
                BigDecimal codProdMP = qItens.getBigDecimal("CODPROD");
                QueryExecutor qComp = contexto.getQuery();
                BigDecimal codProdPA = ComposicaoUtil.buscarCodProdPA(qComp, codProdMP);
                if (codProdPA == null) {
                    contexto.setSucesso(false);
                    contexto.mostraErro(
                        "Composição não encontrada no processo 40 (Beneficiamento Filial)" +
                        " para o produto CODPROD=" + codProdMP + "."
                    );
                    return null;
                }
                mapa.put(codProdMP, codProdPA);
            }
        } finally {
            qItens.close();
        }
        return mapa;
    }

    /**
     * Grava NUMCONTRATO gerado no campo adicional AD_NUMCONTRATO_TRANSF do pedido.
     */
    private void atualizarNumContrato(ContextoRegra contexto,
                                       BigDecimal nuNota,
                                       BigDecimal numContrato) throws Exception {
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUMCONTRATO", numContrato);
            q.setParam("NUNOTA",      nuNota);
            q.nativeExecute(
                "UPDATE TGFCAB SET AD_NUMCONTRATO_TRANSF = {NUMCONTRATO}" +
                " WHERE NUNOTA = {NUNOTA}"
            );
        } finally {
            q.close();
        }
    }
}
```

- [ ] **Step 2: Compilar**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Build JAR**

```bash
./gradlew jar
```
Expected: `BUILD SUCCESSFUL` — arquivo gerado em `build/libs/oasis-transf-contrato-1.0.0.jar`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/oasis/transf/rules/GerarContratoTransferencia.java
git commit -m "feat: GerarContratoTransferencia regra principal orquestra fluxo completo"
```

---

## Task 7: Deploy e Configuração no ERP

**Contexto:** Deploy do JAR + configuração da Regra de Negócio na Central do Sankhya.

- [ ] **Step 1: Copiar JAR para o servidor**

```bash
cp build/libs/oasis-transf-contrato-1.0.0.jar \
   {SANKHYA_HOME}/server/customizacoes/
```

- [ ] **Step 2: Reiniciar o servidor Sankhya**

Via WildFly admin ou serviço Windows/Linux:
```bash
# Linux
sudo systemctl restart sankhya
# ou via script
{SANKHYA_HOME}/bin/stop.sh && {SANKHYA_HOME}/bin/start.sh
```
Aguardar log `Started Sankhya` antes de continuar.

- [ ] **Step 3: Cadastrar Regra de Negócio na Central**

Acessar: **Central de Regras → Regras de Negócio → Nova**

| Campo | Valor |
|---|---|
| Descrição | Gerar Contrato Transferência |
| Instância Principal | CabecalhoNota |
| Instância Secundária | (vazio) |
| Classe Java | `br.com.oasis.transf.rules.GerarContratoTransferencia` |
| Ativo | Sim |

- [ ] **Step 4: Cadastrar Trigger (Instância de Regra)**

Em **Instâncias da Regra → Nova**:

| Campo | Valor |
|---|---|
| Evento | Confirmar |
| Ativo | Sim |
| Filtro (TGFCAB) | `CODEMP = 4 AND CODTIPOPER = 1307` |

- [ ] **Step 5: Teste end-to-end — caminho feliz**

1. Abrir Pedido de Compra (CODEMP=4, TOP=1307)
2. Digitar item com `CODPROD=2` (FEIJAO CARIOCA KG)
3. Na aba **Contrato Transferência**, preencher:
   - `AD_GERCONTRTRANSF` = S
   - `AD_CODSAF` = 1
   - `AD_UNICONVSC` = 60
   - `AD_CODTIPVENDA_CT` = 132
   - `AD_TIPOCONTRATO` = S
   - `AD_QTDNEG_SC` = 47520
   - `AD_VALNEGSC` = 390
   - `AD_DTINIENTREGA` = (data futura)
   - `AD_DTTERMINO` = (data futura)
   - `AD_PERCTOLEXCED` = 10000
   - `AD_TIPOTITULO_CT` = 9
4. Confirmar o pedido
5. Expected: mensagem `"Contrato de Armazém nº XXX e Pedido de Compra (Matriz) nº YYYYY gerados com sucesso."`
6. Verificar TCSCON criado: `SELECT * FROM TCSCON WHERE NUMCONTRATO = XXX`
7. Verificar Pedido Matriz: `SELECT * FROM TGFCAB WHERE NUNOTA = YYYYY`

- [ ] **Step 6: Teste — validação composição ausente**

1. Usar item sem composição no processo 40
2. Confirmar o pedido
3. Expected: erro `"Composição não encontrada no processo 40 (Beneficiamento Filial) para o produto CODPROD=X."`
4. Pedido NÃO confirmado.

- [ ] **Step 7: Teste — flag desativada**

1. Pedido com `AD_GERCONTRTRANSF = N`
2. Confirmar
3. Expected: confirmação normal, SEM geração de contrato.

- [ ] **Step 8: Teste — empresa errada**

1. Tentar confirmar pedido com `CODEMP ≠ 4` (mesmo que a regra não dispare pelo filtro, validação interna deve bloquear se disparar)
2. Expected: erro `"Geração de Contrato Transferência permitida apenas para CODEMP=4"`

---

## Self-Review

### Spec coverage

| Requisito | Task |
|---|---|
| Gatilho ao confirmar Pedido Filial (CODEMP=4, TOP=1307) | Task 7 Step 3-4 |
| Flag AD_GERCONTRTRANSF | Task 6 Step 1 + Pré-req |
| Validar CODEMP=4 | Task 6 Step 1 (`[3]`) |
| Buscar composição processo 40 | Task 2 + Task 6 (`[4]`) |
| Bloquear se composição ausente | Task 6 (`mapearItens`) |
| INSERT TCSCON (CODEMP=1, CODPARC=4) | Task 5 |
| INSERT TCSPSC com CODPRODPA mapeado | Task 5 |
| Invocar gerarPedidoComercializacao nativo | Task 4 + Task 6 |
| Contrato e Pedido Confirmados | Garantido pelo service nativo |
| Feedback com NUMCONTRATO + NUNOTA | Task 6 Step 1 (`[8]`) |
| Gravar AD_NUMCONTRATO_TRANSF | Task 6 (`atualizarNumContrato`) |
| Campos AD_* digitados no Pedido | Pré-req + Task 6 (`lerCabecalho`) |
| CODNAT e CODCENCUS idênticos ao pedido | Task 5 + Task 6 |

Todos os requisitos cobertos. ✓