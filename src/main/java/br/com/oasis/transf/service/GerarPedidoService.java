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
     * @param tcscon     Map com todos os campos do TCSCON criado
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
     * Formato: {"responseBody":{"mensagem":{"$":"83977,"}}}
     * Retorna o primeiro NUNOTA da lista.
     */
    static BigDecimal parseNunota(String response) throws Exception {
        String marker = "\"mensagem\":{\"$\":\"";
        int idx = response.indexOf(marker);
        if (idx < 0) throw new Exception("Resposta inesperada do service Armazem: " + response);
        int start = idx + marker.length();
        int end = response.indexOf("\"", start);
        String raw = response.substring(start, end).trim();
        if (raw.isEmpty()) throw new Exception("NUNOTA nao encontrado na resposta: " + response);
        return new BigDecimal(raw.split(",")[0].trim());
    }
}
