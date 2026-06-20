package br.com.oasis.transf.rules;

import br.com.oasis.transf.service.ContratoArmazemService;
import br.com.oasis.transf.service.FilaPedidoMatrizService;
import br.com.oasis.transf.util.TLogCatcher;
import br.com.oasis.transf.util.TLogConfiguration;
import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.extensions.regrasnegocio.RegraNegocioJava;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import br.com.sankhya.modelcore.util.SWRepositoryUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

        DadosPedido dados = lerCabecalhoEItens(contexto, nuNota);
        if (dados.cabecalho.isEmpty()) return;

        // GATE: nenhuma validação roda sem a flag Gerar Contrato Transferência.
        String flag = (String) dados.cabecalho.get("AD_GERCONTRTRANSF");
        if (!"S".equalsIgnoreCase(flag)) return;

        if (!validarCamposObrigatorios(contexto, dados.cabecalho)) return;

        BigDecimal codEmp = (BigDecimal) dados.cabecalho.get("CODEMP");
        if (codEmp == null || codEmp.intValue() != 4) {
            contexto.setSucesso(false);
            contexto.mostraErro("Geração de Contrato Transferência permitida apenas para CODEMP=4 (Filial).");
            return;
        }

        BigDecimal numContratoExistente = (BigDecimal) dados.cabecalho.get("AD_NUMCONTRATO_TRANSF");
        if (numContratoExistente != null) {
            contexto.setSucesso(false);
            contexto.mostraErro("Contrato de Armazém nº " + numContratoExistente + " já gerado para este pedido.");
            return;
        }

        if (dados.itensComposicao.isEmpty()) {
            throw new Exception("Pedido sem itens para NUNOTA=" + nuNota);
        }

        if (!dados.produtosSemComposicao.isEmpty()) {
            throw new Exception(
                "Composição não encontrada no processo 40 (Beneficiamento Filial) " +
                "para o(s) produto(s) CODPROD=" + dados.produtosSemComposicao
            );
        }

        BigDecimal codProdPA = dados.itensComposicao.values().iterator().next();

        Map<String, Object> tcsconData = ContratoArmazemService.criar(contexto, dados.cabecalho, codProdPA);
        BigDecimal numContrato = (BigDecimal) tcsconData.get("NUMCONTRATO");

        atualizarNumContrato(contexto, nuNota, numContrato);
        enfileirarPedidoMatriz(nuNota, numContrato, contexto.getUsuarioLogado());

        contexto.setMensagem(
            "Contrato de Armazém nº " + numContrato +
            " criado. Pedido de Compra (Matriz) será gerado em background."
        );
    }

    /** Campos obrigatórios ao acionar AD_GERCONTRTRANSF, na ordem de exibição (campo -> descrição). */
    private static final Map<String, String> CAMPOS_OBRIGATORIOS = camposObrigatorios();

    private static Map<String, String> camposObrigatorios() {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("AD_CODSAF",         "Código Safra");
        campos.put("AD_CODTIPVENDA_CT", "Tipo de Negociação (CT)");
        campos.put("AD_TIPOTITULO_CT",  "Tipo de Título");
        campos.put("AD_UNICONVSC",     "Unid. Conversão (SC)");
        campos.put("AD_TIPOCONTRATO",  "Tipo Contrato");
        campos.put("AD_TIPCON",        "Tipo de Contrato");
        campos.put("AD_QTDNEG_SC",     "Quantidade (Kg)");
        campos.put("AD_VALNEGSC",      "Valor Negociado (SC)");
        campos.put("AD_DTINIENTREGA",  "Data Início Entrega");
        campos.put("AD_DTTERMINO",     "Data Término");
        campos.put("AD_PERCTOLEXCED",  "% Tolerância");
        return campos;
    }

    /**
     * Exige que todos os campos de CAMPOS_OBRIGATORIOS estejam preenchidos.
     * Em caso de falta, mostra erro listando as descrições dos campos pendentes
     * e retorna false (chamador deve abortar).
     */
    private boolean validarCamposObrigatorios(ContextoRegra contexto, Map<String, Object> cabecalho) throws Exception {
        List<String> faltantes = new ArrayList<>();
        for (Map.Entry<String, String> campo : CAMPOS_OBRIGATORIOS.entrySet()) {
            if (estaVazio(cabecalho.get(campo.getKey()))) {
                faltantes.add(campo.getValue());
            }
        }
        if (faltantes.isEmpty()) return true;

        StringBuilder msg = new StringBuilder("Os seguintes campos obrigatórios não foram preenchidos:");
        for (String descricao : faltantes) {
            msg.append("\n- ").append(descricao);
        }
        msg.append("\n\nLembre-se: o preenchimento dessas variáveis são obrigatórias.");

        contexto.setSucesso(false);
        contexto.mostraErro(msg.toString());
        return false;
    }

    private boolean estaVazio(Object valor) {
        if (valor == null) return true;
        return (valor instanceof String) && ((String) valor).trim().isEmpty();
    }

    /** Insere linha em AD_GERAPEDMATRIZ pra processamento async pelo Lancador. */
    private void enfileirarPedidoMatriz(BigDecimal nuNota,
                                         BigDecimal numContrato,
                                         BigDecimal codusu) throws Exception {
        try {
            EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
            JdbcWrapper jdbc = dwf.getJdbcWrapper();
            FilaPedidoMatrizService.inserir(jdbc, nuNota, numContrato, codusu);
        } catch (Exception e) {
            TLogCatcher.logError(
                "Erro em enfileirarPedidoMatriz NUNOTA=" + nuNota + " NUMCONTRATO=" + numContrato, e
            );
            throw e;
        }
    }

    /** Cabeçalho + itens com composição em 1 round-trip. */
    private DadosPedido lerCabecalhoEItens(ContextoRegra contexto, BigDecimal nuNota) throws Exception {
        DadosPedido dados = new DadosPedido();
        QueryExecutor q = contexto.getQuery();
        try {
            q.setParam("NUNOTA", nuNota);
            q.nativeSelect(
                "SELECT CAB.CODEMP, CAB.CODNAT, CAB.CODCENCUS, " +
                "       CAB.AD_GERCONTRTRANSF, CAB.AD_CODSAF, CAB.AD_UNICONVSC, " +
                "       CAB.AD_CODTIPVENDA_CT, CAB.AD_TIPOCONTRATO, CAB.AD_QTDNEG_SC, " +
                "       CAB.AD_VALNEGSC, CAB.AD_DTINIENTREGA, CAB.AD_DTTERMINO, " +
                "       CAB.AD_PERCTOLEXCED, CAB.AD_TIPOTITULO_CT, " +
                "       CAB.AD_NUMCONTRATO_TRANSF, CAB.AD_TIPCON, " +
                "       ITE.CODPROD AS CODPRODMP, COMP.CODPRODPA " +
                "  FROM TGFCAB CAB " +
                "  LEFT JOIN TGFITE ITE ON ITE.NUNOTA = CAB.NUNOTA " +
                "  LEFT JOIN ( " +
                "       SELECT MP.CODPRODMP, MP.CODPRODPA, " +
                "              ROW_NUMBER() OVER ( " +
                "                  PARTITION BY MP.CODPRODMP " +
                "                  ORDER BY PRC.VERSAO DESC, PRC.IDPROC DESC " +
                "              ) AS RN " +
                "         FROM TPRLMP MP " +
                "        INNER JOIN TPRATV ATV ON ATV.IDEFX  = MP.IDEFX " +
                "        INNER JOIN TPRPRC PRC ON PRC.IDPROC = ATV.IDPROC " +
                "        WHERE PRC.CODPRC = 40 " +
                "  ) COMP ON COMP.CODPRODMP = ITE.CODPROD AND COMP.RN = 1 " +
                " WHERE CAB.NUNOTA = {NUNOTA}"
            );

            boolean primeira = true;
            while (q.next()) {
                if (primeira) {
                    dados.cabecalho.put("CODEMP",                q.getBigDecimal("CODEMP"));
                    dados.cabecalho.put("CODNAT",                q.getBigDecimal("CODNAT"));
                    dados.cabecalho.put("CODCENCUS",             q.getBigDecimal("CODCENCUS"));
                    dados.cabecalho.put("AD_GERCONTRTRANSF",     q.getString("AD_GERCONTRTRANSF"));
                    dados.cabecalho.put("AD_CODSAF",             q.getBigDecimal("AD_CODSAF"));
                    dados.cabecalho.put("AD_UNICONVSC",          q.getBigDecimal("AD_UNICONVSC"));
                    dados.cabecalho.put("AD_CODTIPVENDA_CT",     q.getBigDecimal("AD_CODTIPVENDA_CT"));
                    dados.cabecalho.put("AD_TIPOCONTRATO",       q.getString("AD_TIPOCONTRATO"));
                    dados.cabecalho.put("AD_QTDNEG_SC",          q.getBigDecimal("AD_QTDNEG_SC"));
                    dados.cabecalho.put("AD_VALNEGSC",           q.getBigDecimal("AD_VALNEGSC"));
                    dados.cabecalho.put("AD_DTINIENTREGA",       q.getTimestamp("AD_DTINIENTREGA"));
                    dados.cabecalho.put("AD_DTTERMINO",          q.getTimestamp("AD_DTTERMINO"));
                    dados.cabecalho.put("AD_PERCTOLEXCED",       q.getBigDecimal("AD_PERCTOLEXCED"));
                    dados.cabecalho.put("AD_TIPOTITULO_CT",      q.getBigDecimal("AD_TIPOTITULO_CT"));
                    dados.cabecalho.put("AD_NUMCONTRATO_TRANSF", q.getBigDecimal("AD_NUMCONTRATO_TRANSF"));
                    dados.cabecalho.put("AD_TIPCON",             q.getString("AD_TIPCON"));
                    primeira = false;
                }

                BigDecimal codProdMP = q.getBigDecimal("CODPRODMP");
                if (codProdMP == null) continue; // cabeçalho sem itens (LEFT JOIN)

                BigDecimal codProdPA = q.getBigDecimal("CODPRODPA");
                if (codProdPA == null) {
                    // Não lança aqui: validação só vale se a flag estiver ligada.
                    // O throw é decidido em executaInterna, após o gate.
                    dados.produtosSemComposicao.add(codProdMP);
                    continue;
                }
                dados.itensComposicao.put(codProdMP, codProdPA);
            }
        } catch (Exception e) {
            TLogCatcher.logError("Erro em lerCabecalhoEItens NUNOTA=" + nuNota, e);
            throw e;
        } finally {
            q.close();
        }
        return dados;
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

    private static final class DadosPedido {
        final Map<String, Object> cabecalho        = new HashMap<>();
        final Map<BigDecimal, BigDecimal> itensComposicao = new LinkedHashMap<>();
        final List<BigDecimal> produtosSemComposicao = new ArrayList<>();
    }
}
