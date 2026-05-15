package br.com.oasis.transf.util;

import br.com.sankhya.extensions.actionbutton.QueryExecutor;
import java.math.BigDecimal;

public class ComposicaoUtil {

    private ComposicaoUtil() {}

    /**
     * Busca CODPRODPA para o CODPRODMP via processo 40 (Beneficiamento Filial), versão mais recente.
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