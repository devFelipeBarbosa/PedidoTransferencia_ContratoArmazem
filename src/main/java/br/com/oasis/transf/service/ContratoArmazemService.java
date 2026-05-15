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

    // Valores fixos de negócio (confirmados no INSERT capturado no monitor)
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

    private ContratoArmazemService() {}

    /**
     * Cria TCSCON + TCSPSC na Matriz (CODEMP=1).
     *
     * @param session   JapeSession aberta no contexto da regra
     * @param pedido    Campos do Pedido da Filial (AD_* + CODNAT + CODCENCUS)
     * @param codProdPA CODPRODPA mapeado via ComposicaoUtil
     * @return          Map com NUMCONTRATO e todos os campos do TCSCON (payload para GerarPedidoService)
     */
    public static Map<String, Object> criar(JapeSession.SessionHandle session,
                                             Map<String, Object> pedido,
                                             BigDecimal codProdPA) throws Exception {
        BigDecimal numContrato = inserirTcscon(session, pedido);
        inserirTcspsc(session, numContrato, codProdPA);
        return buildTcsconMap(numContrato, pedido, codProdPA);
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

        // Campos vindos dos AD_* do Pedido da Filial
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

        // Herdados do cabeçalho do pedido
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

        vo.setField("NUMCONTRATO",   numContrato);
        vo.setField("CODPROD",       codProdPA);
        vo.setField("SITPROD",       "A");
        vo.setField("IMPRNOTA",      "S");
        vo.setField("IMPROS",        "N");
        vo.setField("LIMITANTE",     "N");
        vo.setField("PRODPRINC",     "N");
        vo.setField("QTDEPREVISTA",  BigDecimal.ZERO);
        vo.setField("VLRUNIT",       BigDecimal.ZERO);
        vo.setField("CODUSUALTREG",  BigDecimal.ZERO);
        vo.setField("DHALTREG",      new Timestamp(new Date().getTime()));

        dao.persist(vo);
    }

    private static Map<String, Object> buildTcsconMap(BigDecimal numContrato,
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
