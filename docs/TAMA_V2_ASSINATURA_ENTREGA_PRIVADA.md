# Assinatura e entrega privada do Tama V2 Test

## 1. Guarde um único certificado de teste

Se já existe um keystore que assinou o Test instalado, use **esse mesmo**. Confira
os certificados com `apksigner verify --print-certs app-tamaTest.apk` e
`keytool -list -v -keystore "$HOME/tama-v2-test.jks" -alias tama-test`.
Compare o SHA-256. Uma assinatura nova não recupera os dados da instalação antiga.

Se não existe um keystore compatível, gere uma vez em um computador privado com JDK:

```sh
umask 077
keytool -genkeypair -storetype JKS -keystore "$HOME/tama-v2-test.jks" \
  -alias tama-test -keyalg RSA -keysize 4096 -validity 10000
```

Digite as senhas no terminal quando o `keytool` pedir. Faça backup criptografado
do arquivo e guarde as senhas num cofre. Não envie nada disso por mensagem ou
issue. Não gere outro keystore a cada versão.

No repositório **DaniloXPTEAM/tama-v2**: **Settings → Secrets and variables →
Actions → New repository secret**. Cadastre:

| Nome | Valor |
|---|---|
| `TAMA_TEST_KEYSTORE_BASE64` | Arquivo `.jks` convertido em Base64, sem quebras de linha |
| `TAMA_TEST_STORE_PASSWORD` | Senha do `.jks` |
| `TAMA_TEST_KEY_ALIAS` | Alias existente (ex.: `tama-test`) |
| `TAMA_TEST_KEY_PASSWORD` | Senha da chave |

Para preparar o primeiro valor em Linux ou macOS, fora do repositório:

```sh
umask 077
base64 < "$HOME/tama-v2-test.jks" | tr -d '\n' > "$HOME/.tama-v2-test.b64"
```

Cole diretamente no campo do segredo; apague o temporário após salvar. Um build
com os quatro valores ausentes usa um certificado debug temporário para verificar
a compilação. Uma configuração incompleta falha. A **entrega** exige os quatro
valores e compara o certificado do APK com o do keystore. O app normal não usa
esse assinador.

## 2. Entrega no Meu Drive, sem artefato público

Push na branch de trabalho só valida. O envio exige ação manual a partir de
`main` revisado, após os workflows chegarem à branch padrão. As permissões da
conexão Drive desta conversa **não** passam para o Actions.

1. Em um projeto Google Cloud seu, ative **Google Drive API** e crie um cliente
   OAuth **Aplicativo da Web**. Autorize o URI de retorno
   `https://developers.google.com/oauthplayground`. Use sua própria conta do
   Drive na tela de consentimento; em modo de teste ela precisa estar entre os
   usuários de teste.
2. No [OAuth Playground do Google](https://developers.google.com/oauthplayground),
   abra a engrenagem, marque **Use your own OAuth credentials** e preencha os
   dados do cliente. Na etapa 1, use só o escopo
   `https://www.googleapis.com/auth/drive.file`. Autorize com a conta que será
   dona da pasta. Na etapa 2, troque o código e guarde o `refresh_token`. Tokens
   em modo de teste podem expirar; renove-os quando necessário.
3. No mesmo cadastro de segredos do Actions, crie `TAMA_TEST_DRIVE_OAUTH_JSON`
   com um objeto JSON de três campos: `client_id`, `client_secret` e
   `refresh_token`. Insira os valores diretamente no GitHub; não os coloque em
   chat, commit, input do workflow ou arquivo no repositório.
4. Vá a **Actions → Android verification without uploads → Run workflow**,
   selecione `main`, marque `deliver_to_drive` e execute. A mesma execução
   monta os dois APKs localmente, verifica suas identidades e só transfere o
   APK `tamaTest` assinado. Não há cache de APK, artefato, release ou link
   público de download.
5. Em **Meu Drive**, abra a pasta nova **Tama V2 Test - Entrega privada - …**
   com o hash curto do commit e o número da execução. Confira em **Compartilhar**
   que o acesso geral está **Restrito**. Não compartilhe a pasta durante o envio.
   O workflow exige acesso somente do dono antes de transferir, cria um arquivo
   novo e confere pasta, tamanho, hash e permissões depois. Nenhuma pasta antiga
   é consultada ou alterada. Se o envio ficar inconclusivo, confira a pasta nova
   antes de repetir; não substitua nem apague os arquivos antigos.

Não há ID de pasta no repositório nem no log. A configuração acima foi testada
com respostas simuladas, não com uma conta real do Drive.

## 3. Instalação e teste no telefone, sem cobrança

- Confira que o pacote é `ai.tama.assistant.tamav2test` (**Tama V2 Test**), não
  `ai.tama.assistant`. Compare a assinatura antes de instalar. Se você já usa
  ADB e não tem o APK original, `adb shell pm path ai.tama.assistant.tamav2test`
  lista o caminho instalado sem alterar dados. Copie somente o `base.apk` para
  um diretório privado no seu computador com `adb pull <caminho-do-base.apk>` e
  use `apksigner verify --print-certs` nos dois APKs. Não publique os arquivos.
  Com o mesmo certificado e uma versão compatível, `adb install -r <APK-do-Test>`
  atualiza por cima. **Se o certificado não coincidir, pare:** não desinstale
  nem limpe dados de nenhum app neste roteiro. APKs com a assinatura temporária
  do CI são só para verificação, não para atualizar o Test instalado.
- Use uma instalação de teste sem dados importantes. Se já houver chaves reais,
  não apague nem substitua esses dados para simular uma conta vazia. Ligue o modo
  avião e confirme que Wi-Fi e dados estão desligados. Inicie o Test, escolha
  PT-BR e verifique primeiro **sem nenhuma chave própria**. Abra
  chat e provedores; nenhum filtro de conta própria deve aparecer. A tentativa
  offline, se feita, não comprova que o Cloud responde com rede.
- Em **Adicionar provedor**, selecione OpenRouter, confira `openrouter.ai/api`
  e `/v1` e use a chave fictícia `sk-or-fixture-invalida`. Toque em **Testar
  chave**; offline o resultado é indisponível, não recusada nem sem crédito.
  Volte e teste Groq com `gsk_fixture_invalida`: o prefixo não muda o endereço;
  selecionar Groq preenche `api.groq.com/openai`. Se uma revisão de destinatário
  aparecer, não confirme envio para um endereço diferente.
- Mantenha desativados os dois interruptores de cobrança. Com a chave fictícia
  cadastrada e a rota selecionada, peça “faz uma imagem de uma praia ao
  entardecer”. Deve aparecer o motivo para não enviar a geração, sem texto
  interno nem suposição de cobrança. Responda “sim” ao convite ou “tenta de novo”
  e confira que permanece na mesma conversa. Não espere uma imagem real offline.
- Confira o rodapé de Avatar no fim da rolagem, o voltar em **Adicionar
  modelos**, o cabeçalho do chat em fonte grande, e o link para escolher modelo
  em **Mãos**. Não conceda permissões para Mãos executar ações neste roteiro.
  O modo avião não valida respostas 429/403/5xx, catálogo real ou cobrança.
- A instrumentação isolada pode ser rodada num dispositivo de teste limpo com
  `cd android/src/android && ./gradlew -PtamaTestInstrumentation=true
  :app:connectedTamaTestAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=ai.tama.providers.KeyProbeSectionInstrumentedTest`
  (execute em uma linha, com SDK e dependências nativas preparados). Os testes
  incluem botão desativado sem chave, mudança de endereço e descarte de uma
  resposta atrasada após A → B → A; usam respostas injetadas, sem provedores.
  Em execuções sem entrega, o CI compila essa instrumentação com
  `-PtamaTestInstrumentation=true :app:compileTamaTestAndroidTestKotlin`.
  Compilar não executa o Compose, não instala APK e não valida os formulários
  inteiros ou o Poco. A opção de instrumentação não faz parte do build de entrega.
