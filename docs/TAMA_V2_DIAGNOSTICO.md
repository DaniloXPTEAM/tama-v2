# Diagnóstico local no Android

O comando exato `/diagnostico` (ou `/diagnóstico`) abre uma prévia no chat, sem enviar
uma mensagem ou chamar o modelo. Em **Configurações → Sobre → Diagnóstico** há outra
entrada: ela funciona sem abrir uma conversa. Somente o botão **Copiar diagnóstico**
da prévia grava o texto na área de transferência; nada é enviado automaticamente.
Confira o conteúdo antes de colar, pois aplicativos que tenham acesso à área de transferência
podem ler o que for copiado.

O formato `TAMA_DIAGNOSTIC v1` é uma lista fechada: versão e tipo da instalação, ID conhecido
do aplicativo, API Android, arquitetura conhecida do processador e, quando aberto pelo chat,
perfil selecionado, último perfil usado, estado de resposta/compactação e categoria/código HTTP
da última falha capturada desde que o chat foi aberto. `UNAVAILABLE` significa indisponível;
`OTHER` é outra categoria conhecida, não uma mensagem de erro. Na tela Sobre não há estado de
chat, então esses campos ficam indisponíveis. O perfil `AUTO` é a seleção automática; o último
perfil (`LEAN`, `BASIC` ou `FULL`) é o que o roteador usou, não uma confirmação de sucesso.

O relatório **não inclui** conteúdo de conversa, chave ou credencial, nome de modelo ou provedor,
endereço/endpoint, identificador de conta, caminho de arquivo, texto de erro, log ou ID do aparelho.
Ele não faz verificação de rede nem representa a configuração completa do app. A falha é guardada
apenas em memória e esquecida ao limpar o chat; nem todo tipo de falha tem categoria ou HTTP.

## Teste no celular: oito passos

1. No celular, abra o link da execução Android informado junto à entrega e entre no GitHub, se pedido.
2. Em **Artifacts**, baixe **tama-v2-parallel-test-apk** e abra o ZIP baixado.
3. Extraia e toque em **app-tamaTest.apk**; permita a instalação pelo navegador ou gerenciador de arquivos se o Android pedir.
4. Instale e abra **Tama V2 Test**. Não desinstale nem limpe o TAMA que você já usa.
5. Vá em **Configurações → Sobre → Diagnóstico** e confira a prévia.
6. Toque em **Copiar diagnóstico** e cole em uma nota privada: não deve haver conversa, chave, endereço ou log.
7. Abra um chat, digite somente `/diagnostico` e toque em Enviar; a prévia deve aparecer sem uma mensagem nova no chat.
8. Copie novamente, confira o texto e só compartilhe se concordar. Conte também se alguma etapa falhou.

O APK paralelo é para Android 8 ou superior em aparelho arm64-v8a; ele tem dados privados separados,
mas mídia exportada em pastas públicas pode ser compartilhada e links `tama://` podem abrir o seletor
do Android. O ZIP do GitHub Actions pode exigir login e expira conforme a retenção do artefato.
Uma compilação de teste posterior pode ter outra assinatura debug e não atualizar a anterior; não
apague a instalação atual nem seus dados para contornar erro de assinatura.

## Identidade e limites de verificação

A referência para a identidade TAMA anterior à variante de teste é `d2ed0d5`, não o início da
branch. Nessa referência, o normal já era `ai.tama.assistant` / `TAMA AI`, com o ícone TAMA.
O código de Gradle mantém as configurações normais de debug e release e acrescenta apenas o
build type `tamaTest`, com sufixo `.tamav2test`, rótulo `Tama V2 Test` e assinatura debug. O
manifesto principal e os recursos de ícone também permanecem iguais àquela referência; apenas
textos de diagnóstico foram acrescentados aos recursos de idioma. A verificação de fonte não
prova a assinatura da instalação de uma pessoa nem o comportamento no seu aparelho.

Em comparação com o `main` original `154f076f`, o trabalho anterior da branch **já havia mudado**
o ID de `io.github.nanomuse.app` para `ai.tama.assistant`, o nome de nanoMuse para TAMA AI,
a versão/código, o link profundo e recursos de ícone. Não se deve afirmar que a identidade é
igual àquele `main`. As configurações pessoais da instalação normal não são copiadas para a
variante paralela.

O CI compara o APK paralelo ao debug normal e tenta compilar e inspecionar, separadamente,
o release normal com a chave debug temporária do CI. Essa inspeção não produz um release de
produção nem compara certificados com um celular. Consulte o resultado específico de cada etapa;
a execução de build, unitários, teste de diálogo no emulador, smoke completo e teste no Poco são
provas distintas.
