## Automação de Pedido de Compra e Contrato de Armazém

Esta automação tem por finalidade, ao finalizar a digitação do Pedido de Compra na Filial, gerar um Contrato de Armazém (da Transferência) e ao mesmo tempo o Pedido de Compra desse Contrato (rotina padrão do módulo de Armazém para gerar Pedidos de Compra).

### Requisitos

**Fluxo Idealizado:**
1. **Digitação do Pedido de Compra da Filial**: (CODEMP 4, TOP 1307), com o preenchimento da aba "Contrato Transferência".
2. **Gatilho**: Caso a flag "Gerar Contrato Transferência" esteja ativa ao confirmar o Pedido da Filial, uma Regra de Negócio é acionada para digitação automática do Contrato de Armazém e Geração do Pedido de Compra na Matriz (CODEMP 1, TOP 3006).
3. **Resultado**: É esperada a geração do Contrato e Pedido de Compra ambos Confirmados.
4. **Feedback**: Mensagem na tela informando o `NUMCONTRATO` gerado e o número do Pedido de Compra.

---

### Regras de Itens e Composição
* **Divergência de Itens**: Os itens digitados no Pedido de Compra não serão os mesmos itens para o Contrato (ex: item 2 no pedido vira 16318 no contrato).
* **Vínculo de Produção**: É mandatório buscar essa relação das composições do processo produtivo da Filial.
* **Validações**:
    * Não permitir a confirmação do Pedido da Filial caso a composição não exista.
    * Bloquear a ação caso a empresa do Pedido não seja a Filial (CODEMP 4).

---

### Definições do Contrato de Armazém
* **Parceiro**: 4 - OASIS ALIMENTOS LTDA.
* **Situação**: Ativo | **Modalidade**: Comercialização.
* **Padrão de Classificação**: 88.
* **Configurações Fixas**: Moeda (0), Contato (1), CIF/FOB (F), Exige Pedido na Pesagem (Sim).
* **Campos de Origem (Pedido de Compra Filial)**:
    * Safra, Unidade de Conversão p/ SC, Tipo de Título, Tipo de Negociação, Tipo de Contrato, Quantidade e Valor Negociado, Data de Início da Entrega e % Tolerância.
    * **Datas e Financeiro**: Natureza e Centro de Resultado devem ser idênticos ao pedido original.

> **Importante**: A geração do Pedido de Compra deve invocar a rotina nativa do botão "Gerar Pedido de Compra" dentro da tela de Contratos para garantir as vinculações do sistema.