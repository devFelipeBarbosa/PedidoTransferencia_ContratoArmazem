package br.com.oasis.transf.service;

import br.com.oasis.transf.util.TLogCatcher;
import br.com.sankhya.armazem.model.helper.ArmazensGeraisHelper;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class GerarPedidoService {

    private static final BigDecimal COD_TIP_OPER = new BigDecimal(3006);
    private static final BigDecimal COD_LOCAL    = new BigDecimal(3030100);
    private static final String     SERIE        = "";
    private static final String     CONTROLE     = " ";

    private GerarPedidoService() {}

    /**
     * Gera Pedido de Compra de Comercialização chamando direto
     * ArmazensGeraisHelper.criarNotaComercializacao — mesmo método interno
     * que ContratosArmazemGeralSPBean.gerarPedidoComercializacao invoca.
     * Sem HTTP, sem ServiceContext, sem PlatformService — JVM nativa.
     *
     * @param numContrato NUMCONTRATO do TCSCON recém-criado
     * @param tipcon      AD_TIPCON do pedido da Filial ('B' = Bolsa, requer cotação)
     * @return            NUNOTA do Pedido de Compra gerado
     */
    public static BigDecimal gerar(BigDecimal numContrato, String tipcon) throws Exception {
        EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
        JdbcWrapper jdbc = dwf.getJdbcWrapper();

        String dtEmissao = new SimpleDateFormat("dd/MM/yyyy").format(new Date());

        Map<String, Object> params = new HashMap<>();
        params.put("DTEMISSAO",  dtEmissao);
        params.put("CODTIPOPER", COD_TIP_OPER);
        params.put("SERIE",      SERIE);
        params.put("CODLOCAL",   COD_LOCAL);
        params.put("CONTROLE",   CONTROLE);

        BigDecimal cotacao = "B".equals(tipcon)
            ? buscarCotacao(jdbc, numContrato)
            : BigDecimal.ZERO;

        TLogCatcher.logInfo("Chamando ArmazensGeraisHelper.criarNotaComercializacao NUMCONTRATO="
            + numContrato + " TIPCON=" + tipcon + " COTACAO=" + cotacao);

        BigDecimal nunota = ArmazensGeraisHelper.criarNotaComercializacao(
            jdbc, dwf, numContrato, params, null, cotacao
        );

        if (nunota == null) {
            throw new Exception("criarNotaComercializacao retornou null para NUMCONTRATO=" + numContrato);
        }
        return nunota;
    }

    /**
     * Busca COTACAO em TSICOT para a moeda PPAUTASC do contrato (data mais recente).
     * Replica lógica do SP nativo quando TIPCON='B'.
     */
    private static BigDecimal buscarCotacao(JdbcWrapper jdbc, BigDecimal numContrato) throws Exception {
        NativeSql sql = new NativeSql(jdbc);
        ResultSet rs = null;
        try {
            sql.appendSql(" SELECT TSICOT.COTACAO");
            sql.appendSql("   FROM TSICOT");
            sql.appendSql("  WHERE TSICOT.CODMOEDA = (");
            sql.appendSql("        SELECT CON1.PPAUTASC FROM TCSCON CON1 WHERE CON1.NUMCONTRATO = ?)");
            sql.appendSql("    AND TSICOT.DTMOV = (");
            sql.appendSql("        SELECT MAX(DTMOV) FROM TSICOT");
            sql.appendSql("         WHERE CODMOEDA = (SELECT CON1.PPAUTASC FROM TCSCON CON1 WHERE CON1.NUMCONTRATO = ?))");
            sql.addParameter(numContrato);
            sql.addParameter(numContrato);

            rs = sql.executeQuery();
            BigDecimal cotacao = BigDecimal.ZERO;
            if (rs != null && rs.next()) {
                cotacao = rs.getBigDecimal("COTACAO");
            }
            return cotacao == null ? BigDecimal.ZERO : cotacao;
        } finally {
            NativeSql.releaseResources(sql);
        }
    }
}
