# Tama V2 Test — investigação da rodada 2

## O que o histórico prova

- **FEITO — `154f076fdaed76f1ef5646895a1954dd74d237f9` e `06f9daf^`.** Com base e chave válidas, `ProviderFactory.kt` já construía o cliente OpenAI compatível para `https://api.groq.com/openai/v1`. `OpenAIProvider.kt` usava o ID selecionado e postava em `/chat/completions`, sem veto local pelo preço. `GeminiProvider.kt` também não tinha esse veto. É um caminho utilizável pelo código; o funcionamento da conta gratuita, do modelo `openai/gpt-oss-120b` e dos limites reais nesses commits não foi testado no aparelho.
- **FEITO — `06f9dafa`, primeiro veto de custo no histórico da `main`.** `git log -S'ModelCostGuard.requireAllowed()'` e o diff com o pai mostram a chamada entrando no stream de OpenAI e de Gemini antes do envio. `3711f582` (`main`) contém essa mesma guarda. Compilamos a implementação histórica em JVM: no pacote Test e com consentimento desligado, lançou `Blocked` antes do dublê de envio (0 chamadas); no pacote normal o dublê recebeu 1 chamada. Sem credenciais ou rede. Isso reproduz um mecanismo compatível com a recusa local de 16–41 ms; não prova qual ramo o Poco percorreu em cada turno.
- **FEITO — `43df5eea`, filtro de chat na PR #7 (`d786d9b`).** `FreeModelImport.supports` e `tamaEligibleFreeModelIds` só reconheciam evidência fresca do OpenRouter. `ProviderRepository.tamaAllowsChatModel` e `ModelSlots.chatEntriesOf` removiam as rotas sem evidência quando o filtro era ligado. É um segundo bloqueio, independente de `06f9dafa` e ausente na `main` comparada.
- **FEITO — catálogo local.** `models-dev-api.json` tem 225 provedores. Nos campos `cost.input` e `cost.output`, Groq tem 1/16 zeros, NVIDIA 99/103 e Agnes 2/3. Isso não equivale a preço final ou a acesso por conta. O `openai/gpt-oss-120b` da Groq tem tarifa de plano na amostra; a informação da conta deve ser separada do preço de tabela. O modo filtrado da PR não consultava esse catálogo para os três.

Referências de reprodução: `git show <rev>:android/src/android/app/src/main/java/com/openminis/app/provider/{openai/OpenAIProvider,gemini/GeminiProvider}.kt` (um caminho por vez), `git diff 06f9daf^ 06f9daf` e `git log --ancestry-path -S'ModelCostGuard.requireAllowed()' 154f076..3711f58 -- android/src/android/app/src/main/java/com/openminis/app/provider`.

## Onde uma recusa local pode ocorrer

Caminhos relativos a `android/src/android/app/src/main/java`. Não confundir recepção de HTTP, execução de ferramenta e envio de chat.

| Regra e código | Condição no Test | Mensagem relevante / ação indicada |
| --- | --- | --- |
| `ai/tama/pricing/ModelCostGuard.kt`; transportes `provider/{openai,gemini,anthropic}`, `provider/voice`, `OwnTurns`, `QuickTestSheet`, `ModelUseOffloadHandler`, `MediaOffloadHandler` | Custo não comprovado e consentimento de processo desligado/revogado. Não é resposta HTTP. | Chat compatível: ver lista e custo em Provedores; usar só rotas com evidência da API. Nas demais rotas, selecionar opção comprovada ou revisar a autorização aplicável, nunca ativá-la sozinho. |
| `ai/tama/pricing/FreeModelList.kt`, `ProviderRepository.kt`, `ChatViewModel.kt`, `ModelSlots.kt` | Filtro da conta ou teto zero do OpenRouter; modelo sem evidência atual, ou de fora da lista restrita. | Atualizar e escolher modelo elegível no OpenRouter. Em conta direta compatível, conferir se o provedor divulga evidência para esse modelo e essa rota. |
| `ai/tama/providers/KeyDestinationGuard.kt` | Prefixo indica outro destinatário, sem confirmação para o endereço exato. | Revisar o endereço em Provedores, sem enviar a chave para destino incorreto. Não é uma regra de custo. |
| `ai/tama/router/AutomaticCostGuard.kt`, `ModelRouter.kt`, `AutoModelStore.kt`, `ModelHealth.kt` e seleção/reserva/revalidação em `ChatViewModel.kt` | Auto exige rota habilitada, visível, credencial, aprovação, custo, capacidades, contexto e orçamento. Também pula duplicatas, rotas já tentadas, destinatários não confirmados e cooldowns. | `nm_auto_none` antes ocultava os motivos vindos do seletor. Distinguir aprovação, custo, configuração alterada, contexto e espera, sem mostrar nomes ou erros brutos do provedor. |
| `DirectModelUseGuard.kt`, `VisionGroupResolver.kt`, `AutomaticAvatarGuard.kt`, `AutomaticVideoGuard.kt`, `MediaModelChangeGuard.kt`, `ImageDispatch.kt` | Uso direto durante Auto/edição da política; mídia precisa do modelo e permissão próprios. | Mensagens específicas do uso direto, visão, Avatar, vídeo e imagem. Revisar o slot ou usar o modo manual apropriado; uma rota gratuita de chat não libera esses caminhos. |
| `ChatViewModel.kt` (ferramentas) | Argumentos truncados ou chamada inválida. Não é `ModelCostGuard.Blocked`. | Não executar a ferramenta; tentar novamente com um pedido menor, sem copiar argumentos para o diagnóstico. |

**NÃO VERIFICADO:** status e conta do provedor no Poco, termos de cobrança e acesso aos modelos por plano. Nenhum diagnóstico solicita chaves, payloads ou capturas.
