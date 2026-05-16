package br.com.oasis.transf.service;

import br.com.oasis.transf.util.TLogCatcher;
import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ContratoArmazemService {

    private static final int    CODEMP         = 1;
    private static final int    CODPARC        = 4;
    private static final String ATIVO          = "S";
    private static final String CIF_FOB        = "F";
    private static final int    PADCLASS       = 88;
    private static final int    CODMOEDA       = 0;
    private static final int    CODCONTATO     = 1;
    private static final String EXIGEPEDIDOPES = "S";
    private static final String MODALIDADE     = "C";
    private static final String TIPOARM        = "A";
    private static final String TIPO           = "M";
    private static final String COBPROPORCAR   = "E";

    private ContratoArmazemService() {}

    /**
     * Cria TCSCON + TCSPSC via PL/SQL PRAGMA AUTONOMOUS_TRANSACTION.
     * O COMMIT autônomo torna o registro visível antes do HTTP call
     * ao gerarPedidoComercializacao.
     * NUMCONTRATO gerado via TGFNUM (mesma lógica do ERP).
     */
    public static Map<String, Object> criar(ContextoRegra contexto,
                                             Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {
        BigDecimal numContrato = reservarSequencia(contexto);

        Timestamp now    = new Timestamp(new Date().getTime());
        BigDecimal codusu = contexto.getUsuarioLogado();

        QueryExecutor q = contexto.getQuery();
        try {
            // NC / NC2: mesmo valor, nomes distintos para {PARAM} em dois INSERTs
            q.setParam("NC",           numContrato);
            q.setParam("NC2",          numContrato);
            q.setParam("CODUSU",       codusu);
            q.setParam("DTCONTRATO",   now);
            q.setParam("DTBASEREAJ",   now);
            q.setParam("DHALTREG",     now);
            q.setParam("CODSAF",       toBD(pedido.get("AD_CODSAF")));
            q.setParam("UNICONVSC",    toBD(pedido.get("AD_UNICONVSC")));
            q.setParam("CODTIPVENDA",  toBD(pedido.get("AD_CODTIPVENDA_CT")));
            q.setParam("TIPOCONTRATO", toStr(pedido.get("AD_TIPOCONTRATO")));
            q.setParam("QTDNEG",       toBD(pedido.get("AD_QTDNEG_SC")));
            q.setParam("VALNEGSC",     toBD(pedido.get("AD_VALNEGSC")));
            q.setParam("DTINIENTREGA", toTs(pedido.get("AD_DTINIENTREGA")));
            q.setParam("DTTERMINO",    toTs(pedido.get("AD_DTTERMINO")));
            q.setParam("PERCTOLEXCED", toBD(pedido.get("AD_PERCTOLEXCED")));
            q.setParam("TIPOTITULO",   toBD(pedido.get("AD_TIPOTITULO_CT")));
            q.setParam("CODNAT",       toBD(pedido.get("CODNAT")));
            q.setParam("CODCENCUS",    toBD(pedido.get("CODCENCUS")));
            q.setParam("TIPCON",       toStr(pedido.get("AD_TIPCON")));
            q.setParam("CODPROD",      codProdPA);

            q.update(
                "DECLARE\n" +
                "  PRAGMA AUTONOMOUS_TRANSACTION;\n" +
                "BEGIN\n" +
                "  INSERT INTO TCSCON (\n" +
                "    NUMCONTRATO, CODEMP, CODPARC, ATIVO, CIF_FOB, PADCLASS, CODMOEDA,\n" +
                "    CODCONTATO, EXIGEPEDIDOPES, MODALIDADE, TIPOARM, TIPO, COBPROPORCAR,\n" +
                "    SITCONT, DTCONTRATO, DTBASEREAJ, CODUSU,\n" +
                "    CODSAF, UNICONVSC, CODTIPVENDA, TIPOCONTRATO, TIPCON,\n" +
                "    QTDNEG, VALNEGSC, DTINIENTREGA, DTTERMINO,\n" +
                "    PERCTOLEXCED, TIPOTITULO, CODNAT, CODCENCUS\n" +
                "  ) VALUES (\n" +
                "    {NC}, " + CODEMP + ", " + CODPARC + ", '" + ATIVO + "', '" + CIF_FOB + "', " +
                    PADCLASS + ", " + CODMOEDA + ",\n" +
                "    " + CODCONTATO + ", '" + EXIGEPEDIDOPES + "', '" + MODALIDADE + "', '" +
                    TIPOARM + "', '" + TIPO + "', '" + COBPROPORCAR + "',\n" +
                "    'A', {DTCONTRATO}, {DTBASEREAJ}, {CODUSU},\n" +
                "    {CODSAF}, {UNICONVSC}, {CODTIPVENDA}, {TIPOCONTRATO}, {TIPCON},\n" +
                "    {QTDNEG}, {VALNEGSC}, {DTINIENTREGA}, {DTTERMINO},\n" +
                "    {PERCTOLEXCED}, {TIPOTITULO}, {CODNAT}, {CODCENCUS}\n" +
                "  );\n" +
                "  INSERT INTO TCSPSC (\n" +
                "    NUMCONTRATO, CODPROD, SITPROD, IMPRNOTA, IMPROS,\n" +
                "    LIMITANTE, PRODPRINC, QTDEPREVISTA, VLRUNIT, CODUSUALTREG, DHALTREG\n" +
                "  ) VALUES (\n" +
                "    {NC2}, {CODPROD}, 'A', 'S', 'N',\n" +
                "    'N', 'N', 0, 0, 0, {DHALTREG}\n" +
                "  );\n" +
                "  COMMIT;\n" +
                "END;"
            );
        } catch (Exception e) {
            TLogCatcher.logError("Erro em ContratoArmazemService.criar NUMCONTRATO=" + numContrato, e);
            throw e;
        } finally {
            q.close();
        }

        return buildTcsconMap(numContrato, pedido, codProdPA);
    }

    /**
     * Reserva próximo NUMCONTRATO via TGFNUM (mesma lógica do ERP).
     * Executado na TX principal — bloqueia o counter até commit/rollback.
     */
    private static BigDecimal reservarSequencia(ContextoRegra contexto) throws Exception {
        BigDecimal numContrato;
        QueryExecutor qSel = contexto.getQuery();
        try {
            qSel.nativeSelect(
                "SELECT NVL(ULTCOD, 0) + 1 AS NC FROM TGFNUM " +
                "WHERE ARQUIVO = 'TCSCON' AND CODEMP = 1 AND SERIE = '.'"
            );
            numContrato = qSel.next() ? qSel.getBigDecimal("NC") : BigDecimal.ONE;
        } catch (Exception e) {
            TLogCatcher.logError("Erro em reservarSequencia (SELECT TGFNUM)", e);
            throw e;
        } finally {
            qSel.close();
        }

        QueryExecutor qUpd = contexto.getQuery();
        try {
            qUpd.setParam("NC", numContrato);
            qUpd.update(
                "UPDATE TGFNUM SET ULTCOD = {NC} " +
                "WHERE ARQUIVO = 'TCSCON' AND CODEMP = 1 AND SERIE = '.'"
            );
        } catch (Exception e) {
            TLogCatcher.logError("Erro em reservarSequencia (UPDATE TGFNUM) NC=" + numContrato, e);
            throw e;
        } finally {
            qUpd.close();
        }

        return numContrato;
    }

    public static Map<String, Object> buildTcsconMap(BigDecimal numContrato,
                                                      Map<String, Object> pedido,
                                                      BigDecimal codProdPA) {
        Map<String, Object> m = new HashMap<>();
        m.put("NUMCONTRATO",    numContrato);
        m.put("CODPROD",        codProdPA);
        m.put("CODEMP",         CODEMP);
        m.put("CODPARC",        CODPARC);
        m.put("ATIVO",          ATIVO);
        m.put("CIF_FOB",        CIF_FOB);
        m.put("PADCLASS",       PADCLASS);
        m.put("CODMOEDA",       CODMOEDA);
        m.put("CODCONTATO",     CODCONTATO);
        m.put("EXIGEPEDIDOPES", EXIGEPEDIDOPES);
        m.put("MODALIDADE",     MODALIDADE);
        m.put("TIPOARM",        TIPOARM);
        m.put("TIPO",           TIPO);
        m.put("COBPROPORCAR",   COBPROPORCAR);
        m.put("SITCONT",        "A");
        m.put("CODSAF",         pedido.get("AD_CODSAF"));
        m.put("UNICONVSC",      pedido.get("AD_UNICONVSC"));
        m.put("CODTIPVENDA",    pedido.get("AD_CODTIPVENDA_CT"));
        m.put("TIPOCONTRATO",   pedido.get("AD_TIPOCONTRATO"));
        m.put("QTDNEG",         pedido.get("AD_QTDNEG_SC"));
        m.put("VALNEGSC",       pedido.get("AD_VALNEGSC"));
        m.put("DTINIENTREGA",   pedido.get("AD_DTINIENTREGA"));
        m.put("DTTERMINO",      pedido.get("AD_DTTERMINO"));
        m.put("PERCTOLEXCED",   pedido.get("AD_PERCTOLEXCED"));
        m.put("TIPOTITULO",     pedido.get("AD_TIPOTITULO_CT"));
        m.put("CODNAT",         pedido.get("CODNAT"));
        m.put("CODCENCUS",      pedido.get("CODCENCUS"));
        m.put("TIPCON",         pedido.get("AD_TIPCON"));
        return m;
    }

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
