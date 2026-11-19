# TAMA V2 — Rodada 4 (Test)

## Agnes

- **Adicionar provedor → Agnes** preenche `https://apihub.agnes-ai.com/v1`. É preciso colar a própria chave. A gravação e a releitura usam o caminho conferido em `fc91e4b` (preferências criptografadas + configuração). “Nenhuma chave necessária” era o rótulo de um endpoint compatível que admitia chave vazia; não prova que a chave havia sido perdida. A causa de uma eventual perda no Poco não foi reproduzida.
- O catálogo do chat continua separado. Os dois modelos de imagem são cadastrados pelo app, mesmo que `/models` só traga modelos de conversa. A revisão é **2026-11-19**. A fonte de ambos é **“informado pelo dono (não verificado em documentação do provedor)”**:

  | ID exato | Padrão | Custo tratado pelo app |
  |---|---|---|
  | `agnes-image-2.1-flash` | Sim | Grátis nesta regra local |
  | `agnes-image-2.0-flash` | Não | Grátis nesta regra local |

  A exceção não se estende a chat, outras URLs, chaves de nuvem, outros modelos ou a uma garantia de fatura zero. As páginas públicas de [2.1 Flash](https://wiki.agnes-ai.com/en/docs/agnes-image-21-flash) e [2.0 Flash](https://wiki.agnes-ai.com/en/docs/agnes-image-20-flash) distinguem trial e preço de tabela; não provam a tarifa da chave do dono.
- Essa rota não pede confirmação de custo. As permissões do recurso e, no modo Automático, a aprovação do modelo exato continuam valendo. Uma recusa explícita de pagamento/crédito marca a combinação conta + modelo no registro P0-1 da sessão do app. No Automático, só pode seguir para outro modelo previamente aprovado e elegível como grátis, com nova permissão. Em Manual não troca a escolha da pessoa. Não repete timeout, 503, resposta vazia ou envio ambíguo. O app não observa a fatura nem presume uma cota.
- Contrato **Studio Local v4**, entregue pelo dono: `POST /v1/images/generations`, `Authorization: Bearer` só no cabeçalho, JSON com `model`, `prompt`, `size`, `n: 1`. A edição manual aceita PNG/JPEG/WebP (até 10 MB por referência), com o array `image` no topo. No CLI interno, repita `--from` para mais de uma referência. Só uma rejeição explícita de esquema do campo permite tentar novamente com `extra_body: {"response_format":"url","image":[…]}`; nunca depois de entrega incerta. Sem polling. O prazo total da operação é 120 s. URL de resultado HTTPS vai para GET sem Bearer, sem seguir redirecionamento; `b64_json` e `images[]` também são aceitos.
- Formatos: `1:1` → `1024x1024`, `3:4` → `768x1024`, `4:3` → `1024x768`, `9:16` → `576x1024`, `16:9` e `21:9` → `1024x576`. O arquivo fica com o aspecto devolvido, sem deformar nem recortar. O recibo no chat apresenta o aspecto real. O avatar pede quadrado e usa o enquadramento quadrado já existente. Anexo, markdown inline, miniatura, visualização e compartilhamento usam o fluxo usual.

## NVIDIA: Images API não confirmada

A interface documentada de [FLUX.1 Schnell](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-schnell-infer) e [FLUX.1 Dev](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-dev-infer) é NIM em `ai.api.nvidia.com/v1/genai/black-forest-labs/...`, não `/v1/images/generations` no host de chat. [FLUX.1 Kontext Dev](https://docs.api.nvidia.com/nim/reference/black-forest-labs-flux_1-kontext-dev-infer) ainda tem contrato próprio de referência. Para [Qwen Image](https://build.nvidia.com/qwen/qwen-image/deploy?nim=self-hosted), a documentação examinada é de NIM próprio (`POST /v1/infer`, `artifacts[0].base64`). Para integrar, faltaria confirmar rota hospedada ou implantação, autenticação/acesso do dono, esquema de cada modelo (parâmetros, referências, retorno e erros) e custo/limites daquela conta. Não foi incluído um adaptador NVIDIA de imagem.

## Aceite no aparelho (dono)

1. Baixe o artefato do workflow assinado da Rodada 4. Atualize o **Tama V2 Test** por cima do anterior, sem desinstalar, limpar dados nem substituir o TAMA normal.
2. Adicione **Agnes** com sua chave. Em **Configurações → Modelos → Criar imagens**, escolha **Agnes Imagem 2.1 Flash**. No Automático, aprove o modelo para imagens separadamente; no Manual, basta a seleção e a permissão de criar a imagem.
3. Peça **“crie uma imagem de um gato astronauta”**. Confira a imagem inline, a proporção, abrir em tela cheia e compartilhar. Feche e reabra o app e confira a chave mascarada e a escolha.

Testes de contrato usam servidor simulado e chaves fictícias. A compilação da instrumentação de reabertura não substitui a execução em um aparelho. Instalação, interação no Poco, conta e fatura reais não foram verificadas na Arena.

## Correção após o teste no Poco: seleção Agnes sem troca para pagos

O dono mostrou uma geração concluída no Agnes Studio v4 com **Agnes Image 2.1 Flash**. No APK anterior, o seletor Automático só *preferia* a escolha de imagem: se ela não estivesse elegível ou aprovada, podia selecionar outro modelo já aprovado. E cadastrar de novo o ID semeado como modelo personalizado criava uma duplicata.

- No **Tama V2 Test**, uma escolha em **Criar imagens** fixa o par conta/modelo. Se a escolha estiver sem chave, desativada, não aprovada ou indisponível, o pedido não troca para outro provedor. Sem escolha explícita, uma conta Agnes ativa mantém o padrão dentro da Agnes mesmo com ambas as permissões de custo ligadas. Uma escolha paga explícita válida não é alterada ao instalar a correção.
- A exceção continua sendo uma recusa explícita de pagamento/crédito durante a tentativa Automática: somente outro modelo Agnes declarado grátis e já aprovado pode ser oferecido, com nova permissão; após uma escolha explícita, o alternativo também deve ser da mesma conta. Timeout, fila cheia e envio incerto não abrem alternativa.
- **Adicionar modelo personalizado** reconhece os dois IDs do catálogo Agnes e oferece **Usar para criar imagens**, sem criar uma segunda entrada. Se já houver uma duplicata personalizada antiga, o app usa a única entrada original do catálogo quando inequívoca; não apaga a duplicata nem transfere aprovação. Em Automático, revise e aprove a entrada original, não a cópia personalizada.
- Os testes automatizados usam chaves fictícias. O sucesso visto no Studio não comprova cobrança nem substitui o teste do APK no aparelho. No Poco, atualize por cima, selecione **Agnes Image 2.1 Flash** em **Criar imagens**, mantenha as permissões de custo desligadas, autorize a imagem e confira o modelo mostrado no pedido do gato astronauta.
