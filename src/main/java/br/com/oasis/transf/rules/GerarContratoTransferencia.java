package br.com.oasis.transf.rules;

import br.com.oasis.transf.service.ContratoArmazemService;
import br.com.oasis.transf.service.GerarPedidoService;
import br.com.oasis.transf.util.TLogCatcher;
import br.com.oasis.transf.util.TLogConfiguration;
import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.extensions.regrasnegocio.RegraNegocioJava;
import br.com.sankhya.modelcore.util.SWRepositoryUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class GerarContratoTransferencia implements RegraNegocioJava {

    @Override
    public void executa(ContextoRegra contexto) throws Exception {
        TLogConfiguration.setFileName("GerarContratoTransferencia");
        TLogConfiguration.setPath(SWRepositoryUtils.getBaseFolder() + "/personalizacao");
        try {
            executaInterna(contexto);
        } catch (Exception e) {
            TLogCatcher.logError("Erro em GerarContratoTransferencia NUNOTA=" + contexto.getNunota(), e);
            throw e;
        } finally {
            TLogConfiguration.clear();
        }
    }

    private void executaInterna(ContextoRegra contexto) throws Exception {
        BigDecimal nuNota = contexto.getNunota();
        if (nuNota == null) return;

        TLogCatcher.logInfo("Iniciando regra para NUNOTA=" + nuNota);

        Map<String, Object> cabecalho = lerCabecalho(contexto, nuNota);
        TLogCatcher.logInfo("Cabecalho lido. AD_GERCONTRTRANSF=" + cabecalho.get("AD_GERCONTRTRANSF")
            + " CODEMP=" + cabecalho.get("CODEMP")
            + " AD_TIPCON=" + cabecalho.get("AD_TIPCON")
            + " AD_NUMCONTRATO_TRANSF=" + cabecalho.get("AD_NUMCONTRATO_TRANSF"));

        String flag = (String) cabecalho.get("AD_GERCONTRTRANSF");
        if (!"S".equalsIgnoreCase(flag)) return;

        BigDecimal codEmp = (BigDecimal) cabecalho.get("CODEMP");
        if (codEmp == null || codEmp.intValue() != 4) {
            contexto.setSucesso(false);
            contexto.mostraErro("Geração de Contrato Transferência permitida apenas para CODEMP=4 (Filial).");
            return;
        }

        BigDecimal numContratoExistente = (BigDecimal) cabecalho.get("AD_NUMCONTRATO_TRANSF");
        if (numContratoExistente != null) {
            contexto.setSucesso(false);
            contexto.mostraErro("Contrato de Armazém nº " + numContratoExistente + " já gerado para este pedido.");
            return;
        }

        Map<BigDecimal, BigDecimal> itensMapeados = mapearItensComComposicao(contexto, nuNota);
        if (itensMapeados == null) return;

        BigDecimal codProdPA = itensMapeados.values().iterator().next();

        Map<String, Object> tcsconData = ContratoArmazemService.criar(contexto, cabecalho, codProdPA);
        BigDecimal numContrato = (BigDecimal) tcsconData.get("NUMCONTRATO");
        String tipcon = (String) cabecalho.get("AD_TIPCON");

        TLogCatcher.logInfo("Contrato " + numContrato + " criado para NUNOTA=" + nuNota + ". Gerando pedido via ArmazensGeraisHelper nativo...");

        BigDecimal nuNotaMatriz = GerarPedidoService.gerar(numContrato, tipcon);

        atualizarNumContrato(contexto, nuNota, numContrato);

        TLogCatcher.logInfo("Pedido de compra NUNOTA=" + nuNotaMatriz + " gerado. Contrato=" + numContrato);

        contexto.setMensagem(
            "Contrato de Armazém nº " + numContrato +
            " e Pedido de Compra (Matriz) nº " + nuNotaMatriz +
            " gerados com sucesso."
        );
    }

    private Map<String, Object> lerCabecalho(ContextoRegra contexto, BigDecimal nuNota) throws Exception {
        QueryExecutor q = contexto.getQuery();
        Map<String, Object> dados = new HashMap<>();
        try {
            q.setParam("NUNOTA", nuNota);
            q.nativeSelect(
                "SELECT CODEMP, CODNAT, CODCENCUS," +
                "  AD_GERCONTRTRANSF, AD_CODSAF, AD_UNICONVSC, AD_CODTIPVENDA_CT," +
                "  AD_TIPOCONTRATO, AD_QTDNEG_SC, AD_VALNEGSC, AD_DTINIENTREGA," +
                "  AD_DTTERMINO, AD_PERCTOLEXCED, AD_TIPOTITULO_CT," +
                "  AD_NUMCONTRATO_TRANSF, AD_TIPCON" +
                " FROM TGFCAB WHERE NUNOTA = {NUNOTA}"
            );
            if (q.next()) {
                dados.put("CODEMP",                q.getBigDecimal("CODEMP"));
                dados.put("CODNAT",                q.getBigDecimal("CODNAT"));
                dados.put("CODCENCUS",             q.getBigDecimal("CODCENCUS"));
                dados.put("AD_GERCONTRTRANSF",     q.getString("AD_GERCONTRTRANSF"));
                dados.put("AD_CODSAF",             q.getBigDecimal("AD_CODSAF"));
                dados.put("AD_UNICONVSC",          q.getBigDecimal("AD_UNICONVSC"));
                dados.put("AD_CODTIPVENDA_CT",     q.getBigDecimal("AD_CODTIPVENDA_CT"));
                dados.put("AD_TIPOCONTRATO",       q.getString("AD_TIPOCONTRATO"));
                dados.put("AD_QTDNEG_SC",          q.getBigDecimal("AD_QTDNEG_SC"));
                dados.put("AD_VALNEGSC",           q.getBigDecimal("AD_VALNEGSC"));
                dados.put("AD_DTINIENTREGA",       q.getTimestamp("AD_DTINIENTREGA"));
                dados.put("AD_DTTERMINO",          q.getTimestamp("AD_DTTERMINO"));
                dados.put("AD_PERCTOLEXCED",       q.getBigDecimal("AD_PERCTOLEXCED"));
                dados.put("AD_TIPOTITULO_CT",      q.getBigDecimal("AD_TIPOTITULO_CT"));
                dados.put("AD_NUMCONTRATO_TRANSF", q.getBigDecimal("AD_NUMCONTRATO_TRANSF"));
                dados.put("AD_TIPCON",             q.getString("AD_TIPCON"));
            }
        } catch (Exception e) {
            TLogCatcher.logError("Erro em lerCabecalho NUNOTA=" + nuNota, e);
            throw e;
        } finally {
            q.close();
        }
        return dados;
    }

    // Elimina N+1: 1 JOIN único em vez de 1 QueryExecutor por produto.
    private Map<BigDecimal, BigDecimal> mapearItensComComposicao(ContextoRegra contexto,
                                                                  BigDecimal nuNota) throws Exception {
        Map<BigDecimal, BigDecimal> mapa = new HashMap<>();
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUNOTA", nuNota);
            q.nativeSelect(
                "SELECT ITE.CODPROD AS CODPRODMP, COMP.CODPRODPA" +
                " FROM TGFITE ITE" +
                " LEFT JOIN (" +
                "   SELECT MP.CODPRODMP, MP.CODPRODPA," +
                "          ROW_NUMBER() OVER (PARTITION BY MP.CODPRODMP ORDER BY PRC.VERSAO DESC) AS RN" +
                "   FROM TPRLMP MP" +
                "   INNER JOIN TPRATV ATV ON ATV.IDEFX = MP.IDEFX" +
                "   INNER JOIN TPRPRC PRC ON PRC.IDPROC = ATV.IDPROC" +
                "   WHERE PRC.CODPRC = 40" +
                " ) COMP ON COMP.CODPRODMP = ITE.CODPROD AND COMP.RN = 1" +
                " WHERE ITE.NUNOTA = {NUNOTA}"
            );
            while (q.next()) {
                BigDecimal codProdMP = q.getBigDecimal("CODPRODMP");
                BigDecimal codProdPA = q.getBigDecimal("CODPRODPA");
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
        } catch (Exception e) {
            TLogCatcher.logError("Erro em mapearItensComComposicao NUNOTA=" + nuNota, e);
            throw e;
        } finally {
            q.close();
        }
        return mapa;
    }

    private void atualizarNumContrato(ContextoRegra contexto,
                                      BigDecimal nuNota,
                                      BigDecimal numContrato) throws Exception {
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUMCONTRATO", numContrato);
            q.setParam("NUNOTA",      nuNota);
            q.update(
                "UPDATE TGFCAB SET AD_NUMCONTRATO_TRANSF = {NUMCONTRATO}" +
                " WHERE NUNOTA = {NUNOTA}"
            );
        } catch (Exception e) {
            TLogCatcher.logError("Erro em atualizarNumContrato NUNOTA=" + nuNota, e);
            throw e;
        } finally {
            q.close();
        }
    }
}
