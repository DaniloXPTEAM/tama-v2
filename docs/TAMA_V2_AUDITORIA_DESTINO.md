# TAMA V2 Test: auditoria de destinatário

Revisão estática em 18/11/2026, na branch da PR 6. Não foram enviadas credenciais
nem chamadas aos provedores. A revisão de destinatário é parcial e uma confirmação
não autoriza despesa. Não usar a existência dessa revisão como garantia geral.

## Pontos encontrados

- Cadastro e edição: `AddProviderScreen.kt` e `ProviderDetailScreen.kt` apresentam
  revisão para o par de chave e endereço na variante de teste.
- Descoberta por `ProviderRepository.nmRunRefresh`: verifica chaves API capturadas
  antes de chamar a descoberta. Não comprova todos os chamadores diretos das APIs
  de listagem (`OpenAIModelsApi`, `GeminiModelsApi`, `AnthropicModelsApi`).
- Texto: `OpenAIProvider.rawStreamMessage`, `AnthropicProvider` e `GeminiProvider`
  verificam a chave e a base antes da guarda de custo no provider. A análise não
  prova o fluxo inteiro antes da seleção automática ou da guarda de custo do chat.
- `OpenAIProvider.rawPassthroughRequest`, `generateImage` e `editImage`: não passam
  pela mesma verificação de destinatário. Há overrides de caminho e de cabeçalhos;
  avaliar a URL e a credencial reais depois de aplicar overrides é necessário.
- Voz: `VoiceProvider.buildVoiceInputRequest`, `buildVoiceOutputRequest` e
  `applyVoiceAuth` constroem requisições; `VoiceProviderVendors` contém overrides
  e WebSockets, e `DashScopeVoiceProvider` tem transporte próprio. Não foi provada
  cobertura de destino nessas rotas.
- OAuth: o `OpenAIProvider.buildRequest` pode obter token dinamicamente e usar o
  backend OAuth, enquanto a revisão inicial consulta `apiKey`. Credenciais OAuth,
  renovação, login e listagens OAuth não foram cobertos integralmente. Anthropic e
  Gemini têm caminhos OAuth próprios; a presença de uma chamada à guarda não prova
  revisão do token efetivo. Não inferir que todos os fluxos OAuth estejam protegidos.
- `absoluteEndpointOverride`, `chatExtraHeaders`, cabeçalhos de imagem/passthrough,
  redirects e clientes próprios exigem análise da requisição efetiva. Em texto, a
  verificação atual compara a chave original e a base, não todos os overrides finais.
- Outros serviços de mídia, edição, vídeo, downloads, ferramentas, APIs e caminhos
  não enumerados não foram auditados integralmente.

## Por que não alterar essas rotas nesta rodada

Bloquear ou alterar uma chamada que já funciona, inclusive gateway configurado pelo
usuário, contraria a regra desta rodada sem uma confirmação específica do comportamento
esperado. Uma correção segura precisa de testes da URL e cabeçalhos finais, redirects,
credencial efetiva, interface de confirmação e teste de não regressão da nuvem e do
app normal. Não tratar custo desconhecido como grátis para forçar a chamada.

## Sem chave própria

O teste `ProviderEndpointsTest` e o ensaio host verificam somente que a guarda de
prefixo não bloqueia uma chave nula. Não executam inicialização da Cloud, autenticação,
seleção do modelo, persistência ou conversa. Portanto, o caso completo com zero
chaves próprias **não foi verificado** e ainda precisa de um teste de integração e
um teste no aparelho. Nenhuma configuração ou código da Cloud foi editado nesta rodada.

## Testar chave na tela de cadastro e no provedor salvo

Escolher Groq preenche `https://api.groq.com/openai` e anexa `/v1`. A tela do
OpenRouter mostra a base fixa `https://openrouter.ai/api` e a versão usada. Colar
uma chave só sugere o provedor: não muda nem salva o endereço. Na configuração
antiga de Groq com tipo OpenAI e endereço vazio ou padrão, toque na sugestão e
em **Salvar configurações da URL** para corrigir.

**Testar chave** verifica os destinos oficiais suportados com GET, sem inferência,
redirecionamento ou repetição. No OpenRouter consulta os dados da própria chave;
limite configurado zero não é a mesma coisa que saldo zero ou limite restante
esgotado. Só mostra falta de crédito diante de uma indicação explícita. Um teste
aceito não é teste de conversa. Em gateways o teste oficial fica indisponível,
sem bloquear a configuração existente. Alterar a chave ou o destino descarta o
resultado antigo.
