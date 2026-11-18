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

## Pista da URL vazia: análise adicional

O dono informou que, no teste antigo do Poco, uma chave Groq estava no tipo OpenAI
com a URL base vazia. Na base analisada, o caminho normal do formulário grava um campo
vazio como `null` (`ProviderEndpoints.storageBase`). `ProviderInstance.effectiveBaseURL`
retorna `null` nesse caso e a ramificação OpenAI de `ProviderFactory.create` escolhe
`https://api.openai.com/v1`. Portanto, se essa mesma configuração estiver presente e a
requisição chegar ao envio, o destino configurado não será Groq. Isso não prova que a
requisição antiga saiu, qual foi a resposta, nem que este seja o único motivo do erro.
Se houver suspeita de que uma credencial foi enviada ao destinatário errado, o dono pode
avaliar revogá-la e gerar outra no provedor; não compartilhar a chave ou registros brutos.

Uma string realmente vazia já persistida, diferente de `null`, tem outro comportamento:
`effectiveBaseURL` resulta em `/v1` com o sufixo ligado ou em vazio com ele desligado.
Não há evidência de que esse seja o estado do celular. Não se pode afirmar que isso
produziu um `ProviderError` específico sem executar o caminho e observar o resultado.

No catálogo atual, Groq usa `https://api.groq.com/openai/v1` e OpenRouter usa
`https://openrouter.ai/api/v1`. No pacote paralelo o formulário tem seleção explícita de
provedor, com endereço preenchido por `OwnKeyPresets`; a sugestão por prefixo exige
confirmação. O tipo OpenAI genérico, sem escolher Groq, usa o endereço da OpenAI. O tipo
OpenRouter usa endereço fixo em `ProviderFactory`. A tela real e o estado salvo no
aparelho não foram verificados.

**Proposta, NÃO FEITA:** no paralelo, detectar a combinação suspeita de chave com
prefixo de outro provedor e endereço padrão da OpenAI tanto no cadastro quanto antes
de iniciar uma chamada de uma configuração já existente. Parar localmente e
pedir confirmação do destinatário ou correção do endereço; nunca trocar a URL ou
reenviar a chave silenciosamente. Preservar endereços personalizados e não confiar no
prefixo como prova de identidade. A regra precisa cobrir descoberta de modelos, chat e
outras rotas que possam transmitir credenciais, sem desligar guardas de cobrança.

`tests/host_android/provider_endpoints/run.sh` compila lógica real dos presets e do
tratamento de endereço em JVM, com dublês e trechos extraídos, sem rede. O teste também
confere estática e pontualmente o catálogo e a ligação do formulário; não executa a
fábrica inteira, a tela, o envio HTTP ou Android. Os limites estão no README do teste.

## Proteção parcial de destinatário no pacote paralelo

Foi acrescentada uma revisão local dos prefixos `gsk_` e `sk-or-` no cadastro, na
edição da chave, na descoberta de modelos do repositório e nos envios comuns de texto
OpenAI, Anthropic e Gemini. Ela compara a URL efetiva, sem trocar o endereço; quando
difere, a confirmação exibe o destino antes de permitir prosseguir. URLs inválidas ou
sem HTTPS não podem ser confirmadas. A confirmação é limitada ao processo e ao par
exato de chave e endereço, sem guardar nenhum dos dois. Ela não é autorização de custo;
as guardas existentes continuam independentes. No chat, uma recusa local no caminho
coberto tem texto específico e não deve disparar nova tentativa nem fallback.

**NÃO VERIFICADO:** integração Android, tela, Room, loop completo, erro visível no Poco,
concorrência, redirecionamentos e execução nas duas identidades reais. A rotina JVM
exercita a política, não a transmissão ou o Android. O seletor automático ou a guarda
de custo podem barrar a operação antes de o provider produzir o novo aviso.

**NÃO FEITO:** proteção geral para todos os transportes e todas as credenciais. Em
particular, chamadas de mídia, passthrough, voz, logins OAuth, cabeçalhos substituídos e
outras rotas diretas precisam de auditoria própria. Não tratar a mudança como proteção
completa contra envio incorreto de credenciais ou cobrança.

## Assinatura do pacote de teste

A configuração aceita um keystore externo privado por variáveis de ambiente somente
para `tamaTest`. No workflow, os valores opcionais vêm dos segredos do repositório; o
arquivo temporário é removido ao final e não há etapa de upload. Sem configuração, o
build usa a chave debug e emite aviso: isso **não garante assinatura estável**. Os
segredos não foram criados nesta sessão: a consulta às permissões retornou HTTP 403.
A compilação Android e o uso dos segredos no Actions não foram verificados. Mesmo com
uma nova chave estável, atualizar o APK já instalado só é possível se o certificado for
o mesmo da instalação anterior. Não desinstalar ou limpar dados para contornar isso.

## Número de loops na folha de tokens

No código atual, o número é uma estimativa da conversa inteira: o maior valor
entre a quantidade de mensagens do assistente e a soma de blocos de ferramentas
que não são texto nem informação (`loadSessionTokenStats`). Assim, um valor 11
não demonstra 11 chamadas de API, cobranças ou laços reais. Sem dados do teste
antigo, não é possível identificar quais mensagens originaram esse 11.

### Atualização da revisão de destinatário

A proteção parcial acima foi ampliada para pistas `sk-ant-`, `xai-`, `AQ.` e
`AIza` no mesmo conjunto limitado de rotas. Os testes locais usam chaves fictícias;
as rotas não cobertas continua existindo risco de envio não revisado. Os trechos
anteriores que descrevem apenas dois prefixos são o registro da etapa inicial.

## Texto de atualização da lista

Na variante paralela, a linha de atualizar modelos de um provedor com chave própria
cadastrada usa textos já traduzidos quando a instância Cloud pode ser identificada.
A linha da Cloud, a ausência de chave, a Cloud não identificada e o app normal mantêm
o texto anterior. A condição foi verificada estaticamente; Compose,
o dispositivo e a atualização real da lista não foram executados neste teste.

## Capacidades na seleção automática

A seleção de chaves próprias OpenRouter, no teste paralelo, também usa capacidades
de chat e ferramentas da importação gratuita exata e recente. Isso corrige uma
possível rejeição quando o catálogo incorporado não conhece o modelo importado.
A confirmação de rota, as guardas de custo e rejeições explícitas de capacidade
continuam independentes. Esta correção não prova que essa foi a causa do erro do
Poco; não houve chamada real. `tests/host_android/free_import/run.sh` exercita a
política com dublês, não a tela ou o transporte Android.

## Testar chave no cadastro

No paralelo, o botão envia um GET à lista oficial de modelos dos provedores
OpenAI, Groq, OpenRouter, Anthropic, Gemini e xAI reconhecidos. A chave e o
destino precisam passar pela revisão local; não há redirects ou repetição. A
resposta 403 não é declarada chave inválida, e o sucesso não prova acesso a um
modelo, preço ou saldo. Endereços personalizados não são testados. O teste host
confere planejamento e respostas simuladas, sem executar HTTP; a tela, o
transporte Android e contas reais não foram verificados.
