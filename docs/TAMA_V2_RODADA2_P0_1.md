# Tama V2 Test — P0-1: chat gratuito com chave própria

**FEITO — correção iniciada em `e81aaf4073826297dbc8bd66409041773106a8f7`, PR #7.** A revisão anterior filtrava modelos, mas não permitia o envio por estas três rotas. Agora o app identifica as ofertas; não há declaração de chave gratuita nem mudança automática dos dois interruptores de cobrança.

| Provedor | Evidência usada para permitir o chat no Test |
| --- | --- |
| Groq | IDs de chat da [oferta gratuita documentada](https://console.groq.com/docs/rate-limits): `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `qwen/qwen3.8-27b`. O [teste mínimo](https://console.groq.com/docs/models#get-all-available-models) é um GET autenticado em `/openai/v1/models`, sem inferência. Um retorno normal libera os IDs que também constarem na tabela, com o aviso **Gratuito, com limite de uso**. A tarifa do plano Developer não torna a rota paga no app. HTTP 402 ou uma exigência explícita de pagamento/crédito são tratados separadamente de 403 genérico e 429. O GET não revela o plano nem a fatura. |
| OpenRouter | Preço zero explícito para entrada/saída (e sem preço positivo ou condição nos demais campos) no [catálogo de chat](https://openrouter.ai/docs/api_reference/models) do ID exato. `:free` sozinho não vale, e não é obrigatório. A evidência vale no stream mesmo com a importação filtrada desligada. O POST mantém o ID, usa [`provider.max_price`](https://openrouter.ai/docs/guides/routing/provider-selection#max-price) em zero e desliga fallback. Não pede busca hospedada. Isso não certifica configurações externas de BYOK ou plugins obrigatórios da conta. |
| NVIDIA | Mesmo critério de preço zero e ID exato, somente no catálogo embutido da base `https://integrate.api.nvidia.com/v1`. Há 99 cotações de entrada/saída zero em 103 registros, não 99 modelos de chat disponíveis na conta: mídia, modelos de embeddings/rerank e outros modelos não compatíveis não entram nesta liberação. A tabela não comprova a disponibilidade ao vivo nem vale para outro NIM, plano ou endereço. |

A autorização só vale para o **stream de chat de texto**. Conta, base, ID, formato da requisição e evidência são checados de novo antes do POST. Um erro do stream gratuito não dispara repetição nem troca de provedor. Preço positivo, preço desconhecido, catálogo expirado e falta de crédito não viram autorização. A exceção não permite inferência auxiliar, chamadas diretas por CLI, geração de imagem ou outros endpoints. A rota Gemini já documentada continua separada.

## Aceite em aparelho — NÃO VERIFICADO

No **Tama V2 Test** da revisão da PR que contém a correção, mantenha **os dois interruptores de cobrança desligados** em **Configurações → Modelos → Configurações avançadas de custo**. Não mude essas permissões quando encontrar uma falha.

1. Salve as chaves localmente no app, nos respectivos endereços oficiais. Em **Provedores**, use **Testar chave** na Groq, e atualize os catálogos. Não envie chaves nem capturas de credenciais.
2. Use o modo manual e uma conversa nova para cada provedor. Selecione o ID exato rotulado como gratuito. Candidatos: `openai/gpt-oss-120b` na Groq; no OpenRouter, um ID com cotação atual zero (por exemplo, `liquid/lfm-3-1.7b:free` se ainda constar); na NVIDIA, `nvidia/nemotron-3-nano-30b-a3b`. Use **o catálogo atual**, não um sufixo ou o nome exibido como prova.
3. Envie **“Responda apenas OK.”** em cada rota. Registre o hash instalado, o ID do modelo e se houve resposta com **ambos** os interruptores desligados. Verifique que um ID com preço positivo não envia com a proteção ligada. Se houver 429, registre o limite; não o reporte como falta de crédito.

Os testes JVM de `FreeChatCompletionsWireTest` usam servidores locais e chaves fictícias. Eles não substituem a confirmação pelos três provedores no Poco, não validam nenhuma fatura e não produzem um APK de atualização privada.
