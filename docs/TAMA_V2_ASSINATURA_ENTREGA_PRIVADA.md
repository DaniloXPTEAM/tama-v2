# Assinatura e entrega privada do Tama V2 Test

## Estado

O workflow Android da PR 6 aceita uma assinatura externa para `tamaTest`, mas só
compila e verifica localmente no runner. **Ele não entrega o APK ao Drive.** A
entrega privada automatizada ainda não está pronta nem foi testada; não há upload
neste workflow e nenhum ID do Drive é guardado no repositório.

No build verde 44315048233, o Actions avisou que não havia assinador estável e
usou uma chave debug temporária. Esse build não garante uma atualização por cima
de qualquer APK instalado. O assinador privado opcional não é aplicado ao app normal.

## Antes de criar outra chave

Uma atualização sem desinstalar exige o mesmo certificado de assinatura do APK já
instalado, ou uma rotação de assinatura Android comprovadamente compatível. Uma
chave nova, fixa ou não, não recupera a assinatura antiga. Não desinstale, não
limpe dados e não presuma que dados ou chaves serão preservados se a instalação
for substituída por outro meio. Compare localmente o certificado do APK anterior
com o da chave guardada. Se não existir a chave privada anterior, o procedimento
para manter os dados existentes ainda precisa ser decidido e testado.

## Se já possui o keystore anterior compatível

Guarde cópia de segurança criptografada fora do repositório e não compartilhe o
arquivo ou senhas em chat. Um responsável com permissão no GitHub deve cadastrar,
em Settings → Secrets and variables → Actions → New repository secret, os quatro
segredos abaixo. Use o keystore anterior, não um recém-gerado:

- `TAMA_TEST_KEYSTORE_BASE64`: bytes do keystore codificados em Base64 sem quebras.
- `TAMA_TEST_STORE_PASSWORD`: senha do arquivo.
- `TAMA_TEST_KEY_ALIAS`: alias já existente.
- `TAMA_TEST_KEY_PASSWORD`: senha da chave.

Por exemplo, em um terminal Linux ou macOS privado, `base64 < "$HOME/tama-v2-test.jks" | tr -d '\n' > "$HOME/tama-v2-test-base64.txt"` gera um arquivo temporário
contendo o valor do primeiro segredo. Ajuste o caminho para o keystore real. Copie
o conteúdo diretamente no campo de segredo do GitHub, nunca em chat, issue, log
ou repositório; remova o arquivo temporário após confirmar o cadastro. Não publique
os outros valores. O workflow falha se apenas parte dos quatro for fornecida e usa
uma chave debug temporária com aviso se nenhum for fornecido.

## Se não há APK anterior para atualizar

Para uma instalação nova, um responsável pode criar um keystore local com o JDK:

```sh
keytool -genkeypair -keystore "$HOME/tama-v2-test.jks" -alias tama-test -keyalg RSA -keysize 4096 -validity 10000
```

O programa pede senhas interativamente. Guarde-as e preserve o keystore em backup
privado; depois cadastre os quatro segredos acima. Isso não permite atualizar por
cima de um APK com outro certificado. Evite substituir um arquivo de chave existente.

## Entrega no Drive

Ainda é necessário um workflow manual dedicado que compile e valide o APK no mesmo
runner, exija a assinatura privada sem fallback, envie exclusivamente a uma pasta
privada autorizada, não registre credenciais nem ID e não publique artefatos. A
pasta e suas permissões, a identidade de upload e o certificado precisam ser
verificados antes do envio. A conexão do Drive nesta conversa não fornece por si só
credenciais ao GitHub Actions. Não foi criado arquivo ou pasta no Drive nesta rodada.
