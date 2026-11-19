# Tama V2 Test — Rodada 3

## R3-3: chave e geração de imagens

- **Persistência.** O formulário antigo gravava a chave antes de verificar se já havia um provedor com aquele ID. Um segundo envio com o mesmo ID, depois de uma gravação bem-sucedida, podia falhar na criação do provedor e apagar a chave do primeiro envio. Além disso, uma gravação no armazenamento de recuperação sem criptografia podia parecer bem-sucedida e não ser lida na próxima abertura. O fluxo verificado agora recusa duplicatas antes de alterar a chave, confirma a gravação criptografada e não informa sucesso se um dos armazenamentos falhar. A causa exata no aparelho e o Android Keystore real estão **NÃO VERIFICADOS (sem acesso)**.
- **Agnes.** O catálogo local contém modelos com saída de texto, alguns leem imagens. A [documentação oficial de Agnes Image 2.1 Flash](https://wiki.agnes-ai.com/en/docs/agnes-image-21-flash) indica outro ID, `agnes-image-2.1-flash`, em `POST https://apihub.agnes-ai.com/v1/images/generations`. Para texto → imagem, usa `return_base64: true`, não `response_format` no nível principal. Escolher um modelo de chat não habilita essa rota. A oferta de teste publicada não confirma o preço da chave; **somente gratuitos** não libera a geração. Com o filtro desligado, ainda são necessárias as autorizações de custo e de imagem já existentes. A conta real da Agnes está **NÃO VERIFICADA (sem acesso)**.
- **NVIDIA FLUX.** [Schnell](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-schnell-infer), [Dev](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-dev-infer) e [Kontext Dev](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-kontext-dev-infer) documentam `POST https://ai.api.nvidia.com/v1/genai/black-forest-labs/{flux.1-schnell,flux.1-dev,flux.1-kontext-dev}`, não `/v1/images/generations`. Precisariam de uma integração NIM com o destinatário da chave revisado, a montagem do corpo nativo (`width`, `height`, `steps` etc.) e a decodificação do retorno nativo. A rota de Kontext também precisa da imagem de entrada; a prévia documentada limita as imagens de referência.
- **NVIDIA Qwen Image.** [Qwen Image](https://build.nvidia.com/qwen/qwen-image/deploy?nim=self-hosted) documenta um NIM em infraestrutura própria: `POST /v1/infer` com `prompt` e `seed`, e imagem em `artifacts[0].base64`. Falta configurar um host que sirva esse NIM, implementar esse corpo e retorno e verificar o acesso/preço da rota. Não foi encontrada a mesma rota na API de chat hospedada (`integrate.api.nvidia.com/v1`). Não há geração NVIDIA habilitada nesta entrega nem confirmação de acesso real, preço ou cota (*NÃO VERIFICADO, sem acesso*).

## R3-4: capacidades por variante e rota

As etiquetas no seletor e nos provedores descrevem o catálogo, não uma autorização ou um teste de transporte. Texto, imagem, áudio e vídeo são separados por entrada/saída; código é uma especialidade da saída de texto, não execução de ferramentas. O suporte nominal do modelo e o que o endpoint hospedado expõe podem ser diferentes.

| Variante | Ficha do modelo | Limite da evidência |
| --- | --- | --- |
| [Gemma 4 31B IT](https://huggingface.co/google/gemma-4-31B-it) | Texto e imagem de entrada, vídeo por frames, **sem encoder de áudio** nesta variante. Saída texto, código, contexto nominal de 256K. | No Gemini API, o catálogo exato do app anuncia texto/imagem; NVIDIA e OpenRouter também anunciam vídeo. Isso não habilita envio de vídeo no Tama. |
| [Nemotron 3 Nano 30B A3B](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16) e [Super 120B A12B](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16) | Texto → texto, raciocínio e código; até 1M nominal. | A janela e o preço dependem da rota. No catálogo NVIDIA embutido o Super tem preço positivo. |
| [Nemotron Nano VL 8B v1](https://huggingface.co/nvidia/Llama-3.1-Nemotron-Nano-VL-8B-V1) | Texto e imagem → texto. | A ficha atual e o catálogo embutido anunciam estas modalidades; não importar áudio/vídeo de outro Nemotron. |
| [Nemotron Nano 12B v2 VL](https://huggingface.co/nvidia/NVIDIA-Nemotron-Nano-12B-v2-VL-BF16) | Texto, imagem e vídeo → texto; 128K nominal. | Não anuncia áudio nem gera mídia. |
| [Nemotron 3 Nano Omni 30B A3B Reasoning](https://huggingface.co/nvidia/Nemotron-3-Nano-Omni-30B-A3B-Reasoning-BF16) | Texto, imagem, vídeo e áudio → texto; 256K nominal. | Omni lê mídia; não gera imagem, áudio ou vídeo. |

Os limites nominais acima não substituem a janela efetiva do app ou a do provedor. Sem prova da chave/rota em aparelho: **NÃO VERIFICADO (sem acesso)**.

## R3-5: modelo ativo e uso da conversa

No Test, o **nome do modelo** no cabeçalho abre **Modelo e uso**; o grupo continua abrindo o seletor. A mesma folha é acessível pelo menu de uso quando o cabeçalho está oculto. Mostra provedor, ID, entradas/saídas do catálogo, janela efetiva e máximo de saída quando conhecido. **Trocar modelo** abre o seletor existente e não interrompe uma resposta em andamento. O uso é da conversa, não do modelo atual isoladamente: último contexto informado, uso salvo, cache e etapas confirmadas da resposta quando aplicável. Sem relatório utilizável, mostra `—`; não é fatura. O app normal mantém o atalho anterior.

## R3-6: Gemini — tempo até a resposta e interrupção

O chat nativo continua em `streamGenerateContent?alt=sse`, sem mudar modelo, instruções, raciocínio, contexto ou permissões de custo. No Test, o produtor cancela a chamada HTTP ao parar, mesmo quando ela espera cabeçalhos ou corpo; não depende de terminar a leitura nem reenvia o POST. O relatório `GeminiStream` registra apenas tempos monotônicos de preparo do cliente, cabeçalhos, primeiro **texto** e total, mais o tamanho serializado em bytes. Primeiro evento, pensamento e primeiro texto são coisas diferentes. Isso é medido no transporte, não do toque até a exibição, e não separa rede de fila no Google. É possível comparar novas conversas com o mesmo modelo, prompt, rede e opções, e depois repetir com histórico maior. Não compartilhar chaves, logs gerais ou conteúdo.

O Gemini agora registra o contexto quando a API informa `promptTokenCount`; cache informado é parte desse total, não uma segunda entrada. Metadados parciais sem contagens necessárias não viram zeros. Tempos reais da conta/aparelho e a causa da lentidão: **NÃO VERIFICADOS (sem acesso)**; os ensaios de transporte são sintéticos, sem inferência paga.

## R3-1 e R3-2: roteiro de aceite no Poco

1. Mantenha as duas autorizações de custo desligadas. Em **Provedores**, use **Testar chave** (Groq) e atualize a lista de cada conta. **Somente modelos gratuitos** oculta os pagos conhecidos e rotula os que não foram confirmados; estes últimos não enviam em modo restrito. Desligar o filtro restaura o que estava salvo. Nenhum crédito de teste equivale a custo zero comprovado.
2. No **Manual**, mande um texto curto com um ID elegível; uma recusa não repete o POST. No **Automático**, a outra rota precisa estar aprovada, apta para o pedido e elegível gratuitamente. Antes de qualquer chunk, 429/403 genérico/5xx/timeout podem levar a outra conta+modelo. Não trocar por recusa explícita de política/região, 403 dentro do SSE ou saída parcial. Não aprovar nem habilitar custo para forçar o teste.
3. Confira a notificação de troca e toque no nome do modelo para comparar o ID ativo. A lista, o custo do plano e a resposta do provedor são verificações separadas. Se nenhuma rota alcançar o requisito, a ação é revisar o endereço, a chave, o catálogo ou a aprovação; não recorrer a um modelo pago.

## R3-7: atualização assinada

A entrega excepcional é apenas o **Tama V2 Test** (`ai.tama.assistant.tamav2test`) desta branch e deste SHA. O workflow valida o único certificado fixado antes de publicar `Tama-V2-Test.apk` e `SHA256SUMS.txt` por um dia. Confirme o SHA do commit no run, o SHA do arquivo (não o digest do ZIP) e o certificado. Instale como atualização somente se o pacote e o certificado coincidirem com os do Test instalado. Não desinstale, não limpe os dados, não substitua o APK normal. O build não é teste de instalação, de migração do Keystore nem aceite de uma conta real.
