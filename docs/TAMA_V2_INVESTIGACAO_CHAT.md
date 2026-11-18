# TAMA V2 Test: investigação inicial do chat

Base analisada: `3711f582634721f0c428091489e3776674f90ff9`. O APK testado no Poco,
segundo o relato do dono, não continha essa versão. Esta análise é leitura de código,
não reprodução em Android e não identifica a causa do erro daquele APK.

## O que a leitura mostra

- `ai/tama/router/AutomaticCostGuard.kt` condiciona as rotas automáticas do pacote
  paralelo a uma autorização separada para custo não verificado, mesmo quando um modelo
  é anunciado como gratuito. `AutoModelStore.kt` usa `false` como padrão dessa opção.
- `ai/tama/pricing/ModelCostGuard.kt` inicia bloqueado a cada processo no pacote
  paralelo. `OpenAIProvider.kt` o verifica nos pontos protegidos do envio. Sua autorização
  é separada da autorização de rota automática. A proteção não cobre todos os serviços.
- `ai/tama/pricing/FreeModelImport.kt` filtra importações usando a evidência do catálogo;
  importar um modelo não é autorização de uso automático nem prova de custo zero.
  `ChatViewModel.kt`, na construção das candidatas, fornece a autorização e o bloqueio
  automático separadamente; `ModelRouter.kt` também pode rejeitar capacidade, credencial,
  orçamento, cooldown e outros motivos.
- A frase “Nenhum modelo autorizado atende com segurança…” não identifica por si só
  qual condição falhou. No código atual, ela pode vir da seleção sem candidatas em
  `nmAutoNoCandidate`, ou ser acrescentada após esgotar fallback em grupo. Não se pode
  escolher uma dessas origens apenas pela frase.
- `OpenAIProvider.mapHttpError` retorna `ProviderError` para vários erros HTTP e não só
  para uma causa específica. Eventos da API Responses e outras condições também podem
  produzir esse tipo. O tempo de 0,8 s e o nome da exceção não identificam status, corpo,
  endpoint ou modelo, nem provam se a recusa foi local ou remota.

## Limites e próximos passos

- **NÃO VERIFICADO:** causa do `ProviderError` no Poco, e qual motivo descartou cada
  candidata no teste antigo. Não reproduzir com conta real ou alterar autorizações de
  cobrança para investigar.
- **NÃO FEITO:** correção do chat, liberação segura de modelos gratuitos, build Android,
  APK e teste no aparelho. Não reduzir as guardas enquanto o transporte não puder
  impedir cobranças não autorizadas.
- Para investigar o teste antigo, pedir somente dados que o dono possa fornecer sem
  nova chamada: versão/commit ou hash do APK, provedor e modelo selecionados, se era
  modo automático ou grupo, código HTTP se mostrado e motivos por modelo exibidos pelo
  app. Se o dono optar por copiar um diagnóstico, revisar localmente antes de compartilhar:
  remover chaves, tokens, mensagens, dados de terceiros, URLs privadas e identificadores
  sensíveis. Não publicar capturas ou logs brutos.
- Próxima prova de código: reproduzir a seleção e as respostas do provedor com dados
  fictícios e servidor local, distinguindo bloqueio local, erro HTTP e evento SSE;
  então corrigir somente o caso reproduzido no pacote paralelo. A montagem host não
  substitui compilação Android nem teste real.
