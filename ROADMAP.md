# Roadmap — ii tools

Plano de integração entre o app Android **ii tools** e o shell **ii** (Quickshell).
As fases seguem a ordem de prioridade:

1. **Fase 0 — Fundação**: o que todo o resto precisa.
2. **Fase 1 — Essenciais**: as features mais importantes, que dão razão de ser ao app.
3. **Fase 2 — Baratas**: muito valor por pouco esforço, quase todas sobre ganchos que o shell já tem.
4. **Fase 3 — Caras e úteis**: esforço alto, retorno alto.
5. **Fase 4 — Nicho**: boas ideias, menos gente vai usar.

Dentro de cada fase, as features estão em ordem de prioridade. Esforço: **XS** (horas),
**S** (1–2 dias), **M** (até uma semana), **L** (semanas), **XL** (projeto próprio / spike).

---

## Princípios (valem para toda feature)

### Performance absoluta

O shell e o app têm de continuar leves. Uma integração que o usuário não usa custa **zero**.

| Regra | Shell | App |
| --- | --- | --- |
| Nada roda sem pareamento | O helper do link só sobe com um celular pareado e o link ligado em Settings. Sem `Timer`, `Process` ou `Loader` ativo antes disso. | Nenhum serviço, socket ou wakelock antes de parear. |
| Evento, nunca polling | Estado é empurrado quando muda, reaproveitando os sinais dos serviços que já existem. | O app assina tópicos (`sub`) só enquanto a tela que os mostra está visível e cancela ao sair. |
| Sem processos por comando | O QML chama as funções dos serviços direto; nada de `qs ipc call` ou `hyprctl` por toque. `hyprctl` só via socket do Hyprland já aberto pelo `HyprlandData`. | — |
| Trabalho pesado do lado certo | Miniaturas geradas sob demanda e guardadas em cache. | Imagens redimensionadas/comprimidas no celular antes de enviar (exceto o original quando ele importa, como no wallpaper). |
| Dependências mínimas | Helper nativo único (Rust), runtime `current_thread`, sem Python residente. | OkHttp (WebSocket + pinning) e `org.json` (já vem no Android). Nada de Ktor, Retrofit, Gson ou ML Kit embarcado. |
| Release enxuto | — | R8 + shrink de recursos, Baseline Profile, estado estável no Compose (`@Immutable`, `derivedStateOf`) para evitar recomposição. |

**Orçamentos** (medidos na Fase 0 e checados a cada fase):

- Helper do link: **≤ 8 MB de RSS**, **0 wakeups** com nada mudando, além do keepalive.
- Shell: nenhuma regressão mensurável de RAM/CPU com a integração desligada.
- App: nenhum wakelock parado; com o link ocioso, só o ping do WebSocket (≥ 30 s).
- Toque no deck → ação executada: **< 50 ms** na LAN.

### Interface

- App: **Material 3 Expressive** com Material You (ver `AGENTS.md`): `MaterialExpressiveTheme`,
  `MotionScheme.expressive()`, componentes expressivos, cores só do `colorScheme`.
- Shell: tudo o que o PC controla do celular fica numa **página dedicada `ii tools` no Settings**
  (Fase 0.3). A aba Phone da sidebar pode mostrar estado e atalhos, mas a configuração mora lá.
- Usuário sem o app não vê mudança nenhuma no shell; com o app, cada integração acende sozinha.
- Strings em `values/` e `values-pt-rBR/` no app; `Translation.tr()` no shell.

### Fluxo de trabalho

- **Shell:** toda mudança vai direto para a branch **`dev`** do repo `ii-vynx` (a antiga
  `feat/ii-tools` foi aposentada).
- **App:** branch `master` deste repo.
- Cada feature documenta as duas metades: README aqui, `DOC.md` no shell.

---

## Fase 0 — Fundação

> **Status (2026-11-10): implementada** na branch `feat/ii-tools` do shell e na `master`
> do app, verificada ao vivo com o Galaxy S23. Medido: helper com 3,6 MB de RSS, 2 threads
> e 0 wakeups ocioso; APK de release com 2,7 MB. Desvios do plano: descoberta por
> broadcast UDP na porta do link em vez de mDNS (o firewall padrão do Fedora fecha a
> 5353), e `org.json` no lugar de `kotlinx.serialization`. Falta o teste visual (página
> do Settings e telas do app) e tocar nos botões do controle remoto.

### 0.1 Protocolo ii link · **M**

Canal próprio, bidirecional, entre o celular e o shell. É o que permite comandos, estado ao
vivo e arquivos sem depender do KDE Connect.

- **Transporte:** WebSocket sobre TLS. Frames de texto para mensagens, frames binários (em
  pedaços de 64 KiB) para arquivos e imagens. Sem `permessage-deflate` (custo de CPU maior
  que o ganho em mensagens pequenas).
- **Envelope** (JSON, chaves curtas):

  ```json
  {"t":"hello","v":1,"caps":["remote","deck","wallpaper"],"dev":"Galaxy S23"}
  {"t":"cmd","id":7,"a":"lock.activate","p":{}}
  {"t":"ok","id":7}            {"t":"err","id":7,"code":"disabled"}
  {"t":"sub","topics":["deck","audio"]}      {"t":"unsub","topics":["audio"]}
  {"t":"state","topic":"audio","data":{...}}
  {"t":"blob","id":9,"kind":"wallpaper","name":"x.jpg","size":123456}
  ```

- **Capacidades:** no `hello`, o shell anuncia o que existe e está ligado. O app só mostra o
  que foi anunciado, então app e shell não precisam ser atualizados juntos.
- **Contrato de fio:** `iilink/1`. Mudança incompatível vira `iilink/2`, como o `iilive1:`.
- **Rotas:** LAN (descoberta por broadcast UDP na porta do link), USB (`adb reverse`),
  Tailscale (o shell já tem `TailscaleService`).

### 0.2 Helper e `IiLinkService` no shell · **M**

- **`ii_link` (Rust):** binário único que escuta TLS, responde à descoberta UDP e repassa mensagens. Ele
  **não executa nada**: fala com o shell por JSON em stdin/stdout. Compilado pelo
  `RustHelperBuild` que o shell já usa.
- **`services/IiLinkService.qml`:** sobe o helper via `Process` só quando há pareamento e o link
  está ligado. Recebe os comandos, valida contra a lista de ações permitidas e chama os
  serviços direto. Publica estado só para os tópicos que o app assinou.
- **Segurança:** o celular envia apenas **ids de ação** do catálogo. Comandos arbitrários só
  podem ser criados no PC (página `ii tools` do Settings).

### 0.3 Página `ii tools` no Settings · **M**

Nova página de primeiro nível (`modules/settings/configs/IiToolsConfig.qml`), com config em
`Config.options.iiTools.*`. É o ponto único de controle do celular para tudo o que o PC acessa.

| Seção | Conteúdo |
| --- | --- |
| Instalar o app | **Placeholder** por enquanto: card "Em breve" com o botão desabilitado. Depois: QR para baixar o APK / release. |
| Dispositivo | Parear (mostra o QR), estado da conexão, nome, bateria, rota atual (LAN/USB/Tailscale), desparear. |
| Conexão | Liga/desliga o link, porta, permitir Tailscale, permitir USB. |
| Integrações | Um toggle por integração (live updates, controle remoto, deck, wallpaper, ligações, notificações, clipboard…). Só aparecem as que o app anunciou. |
| Ações e segurança | Catálogo de ações liberadas para o celular; criação de comandos personalizados (só aqui). |
| Diagnóstico | Latência, RSS e CPU do helper, log das últimas mensagens. |

Os toggles que já existem para o celular (por exemplo, os de live updates hoje espalhados pela
aba Phone) migram para cá. A aba Phone ganha um atalho para a página.

### 0.4 Pareamento · **S**

- O shell mostra um QR com `host`, `porta`, fingerprint do certificado e um segredo de uso único.
- O app lê com o scanner do Google Play services (sem permissão de câmera, sem ML Kit no APK).
  Sem câmera ou sem GMS: o PC envia o mesmo link `iilink://pair?…` pelo KDE Connect (ou
  copiado), e o app abre esse esquema.
- Os dois lados fixam os certificados um do outro. Desparear revoga dos dois lados.

### 0.5 Base no app · **M**

- `dev.ii.tools.link`: `LinkHub` (OkHttp, `StateFlow` de conexão e capacidades, descoberta
  por broadcast UDP), `LinkPrefs`.
- **Ciclo de vida:** o link abre quando uma tela precisa dele ou quando há algo a enviar, e fecha
  depois de um tempo ocioso. Integrações que precisam dele sempre aberto (deck em modo dock,
  espelho de notificações) usam um foreground service **só enquanto ativas**. A política final
  sai das medições da 0.6.
- Card "Conexão" no `HomeScreen`, tela de pareamento e base de `Destination`s para as próximas
  features.

### 0.6 Medição · **XS**

Script que mede o RSS e a CPU do helper e do `quickshell` com o link desligado, ocioso e sob uso,
e o consumo de bateria do app (`dumpsys batterystats`). Roda ao fim de cada fase.

**Pronto quando:** o celular pareia por QR, reconecta sozinho na LAN, a página do Settings mostra
o estado ao vivo e os primeiros comandos (`lock.activate`, `oledSaver.toggle`, `mediaMode.toggle`)
funcionam dentro dos orçamentos.

---

## Fase 1 — Essenciais

> **Status (2026-11-11): implementada** (1.1 a 1.6). Pendências registradas em cada item:
> pastas e ícones de apps no deck, navegar pelos wallpapers do PC, mutar o microfone da
> chamada, discar com o app fechado e filtro por app nas notificações. As duas últimas
> esbarram no mesmo limite: o PC não consegue acordar o celular, o que a Fase 3.5 resolve.

### 1.1 Controle remoto · **S**

> **Status (2026-11-11): implementado.** Tela própria no app (`RemoteScreen`): tiles de
> toggle (agora com scratchpad), grade de workspaces, modes com switch e presets. No
> protocolo: tópicos `workspaces`, `modes`, `presets` empurrados inteiros a cada mudança e
> comandos com argumento (`workspace`, `mode`, `preset`). A lista de presets vem de
> `presets.sh list`, lida só na conexão e quando a pasta muda.

Uma tela "Controle" no app com as ações do dia a dia. Todas já existem no shell.

| Ação | Gancho no shell |
| --- | --- |
| Bloquear / AOD | `lock.activate`, `oledSaver.toggle` |
| Media mode | `mediaMode.open/close/toggle`, `setImmersiveLayout` |
| Workspaces | `dispatch workspace N`, lista de workspaces com janelas (tópico `workspaces`) |
| Scratchpad | `togglespecialworkspace`, `scratchpadOverlay` |
| Presets | `presetStore.apply/active/revert` (lista + ativo no tópico `presets`) |
| Modes | `modes.list/activate/deactivate` |

### 1.2 Áudio do PC · **S**

> **Status (2026-11-11): implementado.** Tela `AudioScreen`: volume e mudo da saída e do
> microfone, dispositivo padrão de saída e entrada, volume e mudo por app, EasyEffects
> (ligar/bypass e preset de saída). Tópico `audio` agrupado em no máximo 1 envio a cada
> 60 ms; os nós só são rastreados pelo PipeWire enquanto um celular está conectado.
> Toggle próprio na página `ii tools` (`iiTools.audio.enable`).

Saída e entrada padrão, volume e mute por dispositivo, **volume por app**, mute do microfone,
presets do EasyEffects (`EasyEffects.qml`). Estado vindo do `Audio` do shell (PipeWire), sem
polling. Vai além do plugin de volume do KDE Connect.

### 1.3 Deck · **M**

> **Status (2026-11-11): implementado** (sem pastas). Catálogo do shell = ações de gesto +
> Discord + comandos do usuário (editor na página `ii tools`) + apps sob demanda; modes,
> presets e workspaces entram pelos tópicos que o app já tem. No app: teclas em páginas que
> se ajustam à tela, editar (adicionar por seletor com busca, remover, arrastar para
> reordenar), modo dock (paisagem, tela ligada, brilho baixo, barras escondidas), sem
> serviço em segundo plano. Ícones de qualquer Material Symbol desenhados pelo helper a
> partir da fonte do PC e guardados no celular. Pendências: pastas e ícones reais dos apps
> (hoje inicial do nome).

O celular deitado vira um Stream Deck.

- **Catálogo no shell:** cada ação tem id, ícone, rótulo e, quando é um toggle, **estado ao
  vivo**. Exemplos: gravar (`recordingToolbar`), print / OCR / perguntar à IA (`region`), draw on
  screen (`liveDraw`), keystrokes (`keypress`), mutar o microfone do Discord (`DiscordVoice`),
  HUD de performance (`perfMonitor`), game mode, abrir apps (`LauncherApps`, com ícone), trocar
  preset/mode, mídia, comandos personalizados criados no Settings.
- **App:** grades configuráveis, páginas e pastas, edição por arrastar, toggles acesos na família
  primary container, vibração ao tocar.
- **Modo dock:** paisagem, tela sempre ligada com brilho mínimo, foreground service só enquanto
  o deck está aberto.
- Layouts guardados no celular; o catálogo vem do shell.

### 1.4 Controle de ligações · **M**

> **Status (2026-11-11): implementado**, menos mutar o microfone (só o discador padrão
> controla o áudio da chamada). O vigia mora no `NotificationListenerService` (sempre
> vinculado, sem foreground service) e abre o link sozinho quando toca. Atender, encerrar e
> silenciar via `TelecomManager`; o shell usa o estado do celular no lugar do polling por
> ADB, que fica como fallback. Discar do PC funciona quando o link está aberto; com o app
> fechado e sem chamada, o PC ainda não tem como acordar o celular (fica para 3.5, que
> precisa de um canal sempre disponível).

Substitui o ADB do `PhoneCallService`.

- Estado da chamada via `TelephonyCallback`; atender e recusar via `TelecomManager`
  (`ANSWER_PHONE_CALLS`); discar do PC (`CALL_PHONE`); mutar o microfone.
- O shell continua com o card de ligação que já tem, agora com botões que funcionam sem ADB e
  com o fim da chamada detectado de verdade.
- O áudio da chamada no PC fica para a Fase 3.4.

### 1.5 Wallpaper do celular · **S**

> **Status (2026-11-11): implementado** (sem o bônus de navegar pela pasta do PC). O link
> ganhou transferência de arquivos (cabeçalho + frames binários, gravados pelo helper em
> `incoming/`, limite de 64 MiB). O app é alvo de compartilhamento ("Definir no PC") e tem
> seletor de fotos; o shell salva na pasta de wallpapers e aplica em área de trabalho,
> bloqueio ou ambos. Toggle `iiTools.wallpaper.enable`.

- O app aparece como **alvo de compartilhamento** ("Definir no shell") e também tem um seletor.
- Envia o arquivo original pelo link; o shell salva na pasta de wallpapers e chama
  `wallpapers.apply` ou `wallpapers.applyLockscreen`. As cores se regeneram sozinhas.
- Bônus: navegar pela pasta de wallpapers do PC com miniaturas (geradas e guardadas em cache sob
  demanda).

### 1.6 Notificações pelo link · **M**

> **Status (2026-11-11): implementado.** Espelho no listener, link aberto por rajada (2 min
> após a última notificação, 5 min de pausa se o PC não responde), lista inteira a cada
> conexão, ícone por app enviado uma vez e guardado no PC, imagem reduzida, ações e
> resposta rápida, dispensar nos dois sentidos. Live updates pelo link com `iilive1:` de
> reserva. No shell, a aba Phone e os popups usam o link e deixam de lado as cópias do KDE
> Connect. Pendência: filtro por app.

O app já tem o `NotificationListenerService`; este é o primeiro pedaço do KDE Connect a substituir.

- Espelho de notificações com ícone, imagem grande, ações e resposta rápida.
- **Live updates** passam a ir pelo link em vez do truque da notificação. O marcador `iilive1:`
  continua como fallback quando o link não está ativo.
- Dispensar no PC dispensa no celular, e o contrário também.
- Toggle na página `ii tools`; com ele ligado, o shell ignora as cópias do KDE Connect para não
  duplicar.

---

## Fase 2 — Baratas

> **Status (2026-11-11): concluída**, menos o alarme do celular no clock app (pulado) e o
> hotspot do celular (impossível para app comum).

> **Mudança de base (2026-11-11):** o link passou a ficar aberto em segundo plano
> ("Manter conectado", padrão), com ping a cada 60 s e saída da otimização de bateria;
> sem isso o PC não alcançava o celular parado e as funções PC → celular falhavam.

### 2.1 Quick Settings tiles e widget · **XS**

> **Status (2026-11-11): implementado.** Tiles Bloquear PC, Bloqueio + AOD, Modo mídia e
> Preset do PC (o preset é escolhido com a estrela no Controle remoto). Com o painel aberto
> o link fica de pé e o tile mostra o estado real; um toque conecta, executa e solta.
> Widget com as 8 primeiras teclas do deck, em RemoteViews (sem Glance, que traria
> WorkManager e Room), cores Material You do sistema; sem estado ligado/desligado, porque
> isso exigiria o link aberto.

Tiles do Android para bloquear o PC, AOD, media mode e um preset favorito. Widget com uma mini
grade do deck.

### 2.1b Widgets do shell na tela inicial · **M**

> **Status (2026-11-15): implementado.** Onze widgets de desktop do shell portados na
> proporção do shell (1 célula do shell = 2 do launcher). O lado mais justo da célula fixa a
> escala e o outro cresce até 30% (`Frame.w`/`h`), com cada widget ancorando o layout nas bordas
> reais: preenche a célula sem faixas vazias; o Bloom, sem cartão, fica redondo: Poster, Deck, Halo e Cover (mídia de qualquer app pela sessão de
> mídia, via acesso às notificações), Bloom (relógio em TextClock, o sistema faz o tique),
> Month Tall, Date Type, Date Shapes, Date Banner (eventos com a permissão de agenda),
> Weather Strip e Weather Monolith (Open-Meteo, a mesma fonte do shell, pela localização
> aproximada ou pela rede). Funcionam sem o PC.
>
> Motor (`dev.ii.tools.widgets`): cada widget desenha em unidades do shell numa `Frame` de
> RemoteViews; cada camada é uma máscara alfa recortada ao próprio retângulo e tingida por
> papel de cor (`wr_*`, o `WidgetColorScheme` padrão do shell), resolvido pelo launcher:
> wallpaper e modo escuro mudam sem redesenho. Custos: datas redesenham à meia-noite
> (alarme não-wakeup), clima a cada 30 min só com widget no lugar, mídia a cada faixa ou
> estado e a linha de progresso a 1 Hz só tocando com a tela ligada (atualização parcial de
> 4 camadas pequenas). Prévias geradas no seletor (Android 15+). `WidgetSnapshot` (debug)
> renderiza todos em PNG para revisão.
>
> Próximo: opções por widget (forma da capa, cores do álbum, preenchido) e tamanhos
> responsivos.

### 2.2 Compartilhar para o shell · **S**

> **Status (2026-11-11): implementado.** "Enviar ao PC" no menu de compartilhar: link →
> navegador ou IA ou nota; texto → nota, lembrete, tarefa ou IA; foto ou arquivo → Downloads
> (como o KDE Connect, sem sobrescrever), prateleira ou (imagem) IA. Popup no canto com o
> progresso da transferência e Abrir / Pasta ao concluir. Toggle
> `iiTools.share.enable`.

Pelo menu de compartilhamento do Android: link abre no navegador do PC ou vai para o chat de IA;
texto vira nota (`NotesService`), lembrete (`RemindersService`) ou tarefa (`Todo`); foto vai como
anexo da IA; arquivo vai para o `FileShelf`.

### 2.3 Clipboard com imagens · **S**

> **Status (2026-11-11): implementado.** PC → celular automático enquanto o link está aberto
> (e a última cópia ao conectar), texto e imagem (até 5 MiB), direto no clipboard do Android.
> Celular → PC por ação explícita (o Android só deixa ler o clipboard com o app em foco):
> tile "Copiar para o PC" (abre uma tela invisível por um instante), "Copiar no PC" no
> compartilhar e a tela Área de transferência. Sem eco nos dois sentidos. Opcional: envio
> automático de todo texto copiado, por um serviço de acessibilidade que percebe a cópia e
> lê o clipboard por um instante (o único jeito no Android 10+).

Texto e imagem nos dois sentidos, integrado ao `Cliphist`. Só sincroniza o que o usuário copia
com o app em primeiro plano ou por ação explícita (o Android restringe ler o clipboard em
segundo plano).

### 2.4 Dados do celular sem ADB · **S**

> **Status (2026-11-11): implementado.** Próximo alarme (`getNextAlarmClock`, com o app
> dono) substitui o polling por ADB do `PhoneAlarmService`; contatos com foto (só quando
> mudam, fotos só as que faltam) substituem os vCards do KDE Connect; bateria (nível,
> carregando, temperatura, saúde) e rede (tipo, sinal) em `IiLinkService.phoneStatus`.

- Próximo alarme (`AlarmManager.getNextAlarmClock`) para o `PhoneAlarmService`.
- Contatos com foto (`ContactsContract`) para o `PhoneContactsService`.
- Bateria detalhada (temperatura, saúde, carregamento rápido), tipo de rede e sinal.

### 2.5 Não Perturbe nos dois sentidos · **S**

> **Status (2026-11-11): implementado.** O DND do shell e os modes escolhidos silenciam o
> celular (pelo listener, sem acesso ao Não Perturbe); o DND do celular liga o DND do shell
> ou um mode. Só transições são enviadas, então desligar à mão não é desfeito. Com o
> celular longe, a mudança vai na próxima conexão.

Modes do shell (game mode, foco, bedtime) ligam o Não Perturbe do celular, e o Não Perturbe do
celular pode ativar um mode no shell. Mapeamento configurável na página `ii tools`.

### 2.5b Alarmes do celular no clock app do shell · *pulado*

> **2026-11-11: pulado a pedido.** O Relógio do Google não deixa outro app listar nem
> alterar alarmes ("No external queries"); o Sleep as Android só deixa ler (permissão
> `com.urbandroid.sleep.READ`); sem ADB o Android informa só o próximo alarme do aparelho.
> O caminho viável seria o próprio ii tools tocar os alarmes marcados como "Celular" no
> clock app do shell.

### 2.6 Live Updates do PC no celular · **S**

> **Status (2026-11-11): implementado.** Gravação, pomodoro, cronômetro, contagens, alarme
> tocando e progresso viram Live Updates (Android 16, `ProgressStyle`) com botões. Horários
> absolutos: o celular conta sozinho, e os timers continuam se o link cair.

O caminho inverso do relay: gravação em andamento, timers (`TimerService`), alarmes
(`AlarmService`) e progressos (`ProgressService`) viram Live Updates (`ProgressStyle`) na tela
de bloqueio do celular, com botões (parar a gravação, pausar o timer).

### 2.7 Screenshot entregue · **XS**

> **Status (2026-11-11): implementado** (adiantado a pedido). Print do PC (região ou tela
> cheia) vai para a galeria do celular (Imagens/ii tools) com notificação, na hora ou na
> próxima conexão (fila de 5). Print do celular vai para `~/Pictures/Screenshots/Phone`, para
> o clipboard e abre o preview de print do shell. O link ganhou envio de arquivos do PC
> para o celular (`sendFile`). Envio automático opcional; desligado, o preview de print
> oferece "Salvar no celular".

Print tirado no PC aparece como notificação no celular, com a imagem. Print tirado no celular
aparece no popup de preview do shell (`ScreenshotOverlay`).

### 2.8 Hotspot e Wake-on-LAN · **XS**

> **Status (2026-11-11): Wake-on-LAN implementado; hotspot não.** O shell manda no `hello`
> os MACs e broadcasts dos adaptadores; o app guarda e envia o pacote mágico (linha em
> Conectar ao PC e tile). Depende de WoL ligado no firmware/adaptador. Ligar o hotspot do
> celular não é possível para app comum (API de sistema `TETHER_PRIVILEGED`); só com ADB,
> root ou Shizuku.

Ligar o hotspot do celular pelo shell (`Hotspot.qml`); acordar o PC pelo app (magic packet).

### 2.9 Painel de desempenho · **S**

> **Status (2026-11-11): implementado.** Tela Desempenho: CPU, GPU (com VRAM e potência),
> RAM e FPS (MangoHud), uma amostra por segundo, só com a tela aberta (o PC liga o sampler
> do HUD só durante a assinatura), tela sempre ligada, quatro cartões lado a lado deitado.

Com o game mode ativo, o celular deitado mostra CPU, GPU, temperaturas, RAM e FPS (dados do
`ResourceUsage`, tópico assinado só com a tela aberta, no máximo 1 Hz). Pode ser uma página do
deck.

---

## Fase 3 — Caras e úteis

### 3.1 Celular como monitor (espelhar e estender) · **XL**

- **Estender:** saída virtual no Hyprland (`hyprctl output create headless`), captura dessa saída,
  encode por hardware (VA-API/NVENC), decode no celular com `MediaCodec` numa `SurfaceView`.
  O toque volta como mouse/touch via uinput (`Ydotool`).
- **Espelhar:** a mesma pipeline apontada para uma saída real.
- **MVP:** orquestrar Sunshine + Moonlight (o shell cria a saída e liga o Sunshine nela).
  **Depois:** cliente próprio dentro do app, com USB (`adb reverse`) para menor latência.
- O scrcpy não serve aqui: ele só transmite do celular para o PC.

> **Começado (2026-11-13), só por USB.** Cliente próprio em vez de Sunshine + Moonlight:
> saída headless do Hyprland (estender) ou a tela em foco (espelhar), wf-recorder com
> NVENC/VA-API/x264 em FLV, socket local trazido ao celular por `adb reverse` com token de
> uso único, `MediaCodec` de baixa latência numa `SurfaceView`, toque de volta como
> touchscreen uinput mapeada na saída. Espelhar escolhe a tela (e troca durante a
> sessão); a barra flutuante tem teclado, desenhar na tela e parar; resolução, fps, escala,
> lado e qualidade vêm do app.
>
> **Versão sem fio (2026-11-15): implementada.** Socket lateral `/iilink/1/screen` na porta
> TLS do link (mesmo pinning, token de uso único, sem adb), escolhido por Conexão
> (Automática / USB / Wi-Fi; Automática usa USB quando há cabo). Fila de envio com idade
> medida e `TCP_NOTSENT_LOWAT`, descarte até o próximo quadro-chave, bitrate adaptativo
> (×0,7 / ×1,25), GOP de 2 s, reconexão em até 10 s, rádio Wi-Fi acordado (`MirrorRadio`
> por dono). O app mostra taxa, latência e perdas ao vivo e muda a qualidade sem parar.
> Toggle "Phone as a monitor over Wi-Fi" na página ii tools do shell.
>
> **Ferramentas da sessão (2026-11-15):** toolbar ancorada na alça (um `Layout` só, sem
> saltos), trackpad, zoom no mirror, workspaces e shell tablet no mirror, lançador na tela da
> sessão, teclas extras acima do teclado, áudio do PC no celular (Opus pela sessão Wi-Fi,
> sink virtual "Phone") e extend em retrato.
>
> **Áudio do PC no celular (2026-11-15):** não tocava porque o helper `ii_link` instalado
> era anterior ao código de áudio do `screen.rs` (o shell só marca o helper como
> desatualizado em *Settings → ii tools*); recompilado, funciona. A janela flutuante do
> scrcpy agora acompanha a rotação do celular (o `video_size` vem da própria saída do
> scrcpy) em vez de ficar com o formato retrato e barras pretas.
>
> **Depois:** HEVC na rede (FLV do wf-recorder não leva HEVC; precisa de outro mux) e
> lembrar o encoder que funcionou para reinícios mais rápidos.

### 3.2 Celular como autenticador · **L**

> **Pulado (2026-11-13):** pouca utilidade para o custo e o cuidado de segurança.

Aprovar pedidos do polkit (`PolkitService`), `sudo` (`AskpassService`) e desbloquear o lockscreen
com a biometria do celular (`BiometricPrompt` + chave no Android Keystore assinando um desafio
do shell). Precisa de desenho de segurança cuidadoso: desafio de uso único, expiração curta,
sempre opcional, nunca substitui a senha como fallback.

### 3.3 Presença · **M**

> **Pulado (2026-11-13):** não vale o custo e o esforço (depende da 3.2 para o desbloqueio).

Celular se afasta (link cai ou BLE fraco) → o shell bloqueia. Celular volta → o shell oferece
desbloqueio rápido (com a 3.2). Usa o advertising BLE só quando a feature está ligada.

### 3.4 Áudio das ligações no PC · **XL** (começa com spike)

> **Spike feito e implementado (2026-11-13).** O PipeWire 1.6 já faz o papel HFP-HF pelo
> backend nativo (sem oFono); com o S23 conectado só no perfil HFP, uma ligação de verdade
> saiu no fone do PC, e o WirePlumber ligou sozinho a voz à saída padrão e o microfone
> (via EasyEffects) à ligação. O shell conecta o perfil só durante a ligação: "Answer on
> this PC" no popup de chamada, "Move audio to this PC/phone" durante ela, e desconecta
> ao fim. O microfone não pôde ser testado ponta a ponta (caixa postal).

O Android não deixa app de terceiros capturar o áudio de uma chamada. O único caminho é o PC
virar um fone Bluetooth (perfil HFP, papel HF). **Spike primeiro:** testar o suporte a HFP-HF
no PipeWire / oFono da distro. Se funcionar, o shell roteia microfone e alto-falante e o app
(com a 1.4) cuida do controle.

### 3.5 Substituir o resto do KDE Connect · **L**

> **Em andamento (2026-11-11).** Passo 1 feito: o shell lista o celular do ii tools como um
> dispositivo do `KdeConnectService` e o prefere ao KDE Connect para o mesmo celular; a aba
> Phone, o dock, widgets e toggles funcionam sem KDE Connect; tocar para achar, ping, link,
> texto e arquivo para Downloads vão pelo link. Passo 2 feito: textos e telas vazias neutros
> ou com o backend certo; "Pair with ii tools" na aba Phone; KDE Connect deixou de ser
> obrigatório. Passo 3a feito: a mídia do celular vira players MPRIS no PC (helper com zbus,
> lado do app a partir do Sefirah). Passo 3b feito: o player ativo do PC vira uma sessão de
> mídia no celular (tela de bloqueio e Quick Settings) com play, pular e seek. Passo 4 feito:
> os arquivos do celular no gerenciador de arquivos do PC (SFTP + sshfs, a partir do
> KDE Connect). Próximos: teclado e mouse remotos, comandos.

Uma feature por vez, com o KDE Connect como fallback enquanto o app não estiver pareado:
SMS (ler e responder), controle de mídia do PC no celular (`MediaSession`), achar o celular,
envio de arquivos, ping, compartilhamento de URL. Cada uma vira um toggle na página `ii tools`.

### 3.6 Touchpad e teclado · **M**

> **Feito (2026-11-12).** Celular → PC: touchpad uinput multitouch (os gestos e
> configurações do touchpad do usuário valem) e teclado virtual do Wayland (qualquer
> caractere). O PC → celular (remotekeyboard do KDE Connect) foi **pulado** a pedido
> (2026-11-13): não é necessário.

Celular como touchpad (gestos de vários dedos mapeados para o Hyprland) e teclado, via uinput.
Substitui o mousepad e o remotekeyboard do KDE Connect.

---

### Link estável

> **Feito (2026-11-15):** serviço em primeiro plano `connectedDevice` enquanto "Manter
> conectado" está ligado. Antes o processo ficava em adj 250 (só o leitor de notificações) e
> o lmkd do S23 o matava várias vezes por minuto; após uma morte por memória o Android chegou
> a agendar o reinício para 5 min depois.
>
> **Pendente:** o helper morre a cada recarga do Quickshell (qualquer QML salvo), derrubando o
> celular por ~6 s. Solução: helper em processo próprio falando com o shell por socket Unix,
> saindo sozinho quando o shell some de vez.

## Fase 4 — Nicho

### 4.1 Mesa de desenho para o LiveDraw · **M**

Desenhar no celular (com pressão, se houver caneta) e o traço aparecer no `liveDraw` do PC.

> **Feito (2026-11-13).** A mesa é a tela em foco (mesmo formato); os pontos passam pelo
> motor do live draw (suavização, ferramentas, borracha, desfazer do PC).

### 4.2 Apresentação e teleprompter · **S**

Passador de slides (setas, laser na tela) e controle do `Teleprompter` (rolagem, velocidade).

> **Apresentador feito (2026-11-13).** Teclas pelo teclado virtual do helper (qualquer app
> de apresentação), laser como ponto da shell sobre a tela em foco, teclas de volume
> passam slides, cronômetro. **Teleprompter pulado:** não vale o custo agora.

### 4.3 Ditado pelo celular · **M**

Microfone do celular alimentando o `DictationService` do shell.

> **Pulado (2026-11-13):** não vale o custo e o esforço.

### 4.4 Health Connect · **M**

Passos, sono e batimentos para o `HealthService` do shell.

> **Pulado (2026-11-13):** não vale o custo e o esforço.

### 4.5 Miniaturas ao vivo dos workspaces · **M**

A tela de workspaces da 1.1 ganha miniaturas reais das janelas, capturadas sob demanda e com
baixa resolução.

### 4.6 Câmera própria · **L** · feito

O celular como webcam do PC, no lugar do DroidCam sempre que o ii tools é o serviço ativo
(Settings → ii tools → "Usar o DroidCam" volta a ele).

- **Celular:** Camera2 + `MediaCodec` (H.264 do encoder de hardware, sem B-frames), prévia na
  tela da webcam em 4:3 ou 16:9 (16:9 vira layout flutuante), lentes 0.6/1×/3× como zoom da
  câmera lógica, frontal, exposição, foco (toque e manual), lanterna, estabilização. Um
  foreground service de câmera mantém o stream com a tela desligada. O PC pode pedir: o
  celular confirma por notificação (Android só abre a câmera com o usuário na frente).
- **Transporte:** um segundo WebSocket na porta do link (`/iilink/1/camera`, mesmo TLS e
  pinning, token de uso único), sem outra porta nem rota; quadros descartados até o próximo
  key frame quando a rede engasga.
- **PC:** o helper decodifica para um `v4l2loopback` ("ii tools camera", que todo app lista)
  com ffmpeg, ou, sem o módulo de kernel, para uma câmera do PipeWire (GStreamer), sem root
  mas lida por menos apps. Espelhar e girar ficam no PC.

### 4.7 Galeria do celular · **M**

As fotos e os vídeos do celular no PC, como no Phone Link: uma página **Galeria** na aba
Phone da sidebar (ao lado de Contatos e Android Apps), em grade de 4 colunas, mais recentes
primeiro, agrupadas por data e com filtro por álbum. Um clique abre o visualizador central
(setas para navegar) com Salvar em `~/Pictures/Phone`, Copiar, Abrir no app padrão, Mostrar
na pasta e arrastar para outros apps. **Sem apagar pelo PC.**

- **Celular:** `MediaStore` (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, só leitura) lido em
  páginas de ~200 sob demanda; miniaturas com `ContentResolver.loadThumbnail` só das
  visíveis; um `ContentObserver` avisa o PC de cada foto nova na hora.
- **Transporte:** a lista em JSON pelo link; as miniaturas em binário, gravadas pelo
  próprio helper no cache (`~/.cache/quickshell/phone_gallery/<dev>/`), sem passar pela
  shell arquivo a arquivo; a foto inteira pelo link, em cache (HEIC vira JPEG no envio);
  vídeos pelo SFTP que já existe, para tocar e avançar sem baixar tudo.
- **Etapas:** lista e miniaturas → visualizador e ações → vídeos e fotos novas ao vivo.

---

## Fase 5 — Dia a dia

Saída da auditoria de 2026-11-14: buracos deixados ao tirar o KDE Connect e integrações de uso
diário que se apoiam em ganchos que o shell já tem. Mesmo critério das fases anteriores: só
entra o que tem uso diário ou quase.

### 5.0 Buracos da auditoria · **XS–S**

> **Status (2026-11-14): três primeiros itens implementados, falta o teste no aparelho.**
> Mídia pausada na ligação (`PhoneCallService._setState`, vale para link e ADB); filtro por
> app (`notifApps`/`notifFilter`, lista no app e no Settings); pastas de um nível no deck e
> ícones reais (`get appIcons` → `appIcon`, renderizados sob demanda por
> `render_app_icons.py`, um processo por lote). SMS e medição ainda não.

- **Pausar a mídia do PC na ligação:** o celular tocando ou em chamada pausa os players MPRIS
  que estavam tocando; ao fim da chamada, retoma só os que o shell pausou. Era um plugin do
  KDE Connect. Toggle `iiTools.calls.pauseMedia`.
- **Filtro de notificações por app:** pendência da 1.6. A lista de apps do celular vem pelo
  link; o filtro mora no celular (o app nem envia o que está bloqueado) e é editável dos dois
  lados.
- **Pastas e ícones reais no deck:** pendência da 1.3. Pastas como tecla que abre uma página
  própria; ícone do app do PC rasterizado pelo helper a partir do tema de ícones, guardado no
  celular como os símbolos.
- **SMS:** ler conversas e responder (`READ_SMS`/`SEND_SMS`, APK fora da Play Store). Hoje só a
  resposta rápida da notificação cobre.
- **Medição:** rodar de novo o script da 0.6 (câmera, monitor e galeria entraram depois da última).

### 5.1 Códigos 2FA no PC · **S**

> **Status (2026-11-14): implementado, falta testar com um SMS real.** Sem mudança no app: o shell
> acha o código no texto da notificação espelhada (palavra-chave + número de 4–8 dígitos, ignora
> valores, horas e datas), copia com `wl-copy --sensitive` (fora do `Cliphist`), mostra o popup com
> Copiar e limpa o clipboard depois de 60 s. Funciona em Não Perturbe (só o popup some). Risco: o
> Android 15+ pode esconder o texto de notificações com código de apps que não são o de SMS padrão;
> os testes por `cmd notification post` chegaram inteiros.

Código de verificação que chega no celular (SMS ou app) vira um popup no PC com "Copiar" e vai
direto para o clipboard. Extraído do texto da notificação já espelhada, sem permissão de SMS.
Some do clipboard depois de um tempo, sem entrar no histórico do `Cliphist`.

### 5.2 O PC avisa quando você está longe · **S**

Com o PC ocioso ou bloqueado (`Idle`), eventos do shell viram notificação no celular: comando
longo terminado (gancho `precmd` do zsh, com duração mínima), download concluído
(`jobview_monitor`), progresso terminado (`ProgressService`), resposta da IA pronta
(`AiResponseBus`), lembrete vencendo (`RemindersService`). Cada fonte com seu toggle.

### 5.3 Câmera e scanner para o PC · **S**

Estilo Continuity Camera: um tile/atalho no PC pede "foto" ou "escanear documento"; o celular
abre a câmera ou o scanner de documentos do Play services (sem permissão de câmera, como o QR) e
o resultado cai no clipboard, em Downloads (PDF) ou como anexo da IA.

### 5.4 Deck por app em foco · **S**

> **Status (2026-11-14): implementado, falta o teste visual e no dia a dia.** (a) Modos e rotinas do Samsung: atalhos
> de app (um por regra "manual", mais fixos) e o construtor "Criar atalho" (`CreateShortcutActivity`, o
> `CREATE_SHORTCUT` que o Routines da Samsung lista, confirmado no APK dele junto com `LauncherApps.getShortcuts`),
> que monta atalho de qualquer ação do PC com o valor; a atividade exportada exige o token da instalação.
> (b) Regras editáveis no app (`routines/Rules.kt`, `Routines.kt`): gatilhos carregador, ligação, Não perturbe,
> Wi-Fi de casa, bateria ou manual, janela de horário, passos e passos de fim; templates saindo/chegando em casa,
> hora de dormir, em ligação, celular silenciado. Os passos usam o catálogo do próprio Modes do shell
> (`get modeActions`, `cmd modeAction`, ~32 ações, sem as que dariam shell ao celular), ações do shell, presets,
> Wake-on-LAN, ações do celular (`PhoneControl`: DND, campainha, volume, lanterna, brilho, rotação, mídia,
> notificação, fala, vibração) e espera. (c) O Modes & Routines do shell ganhou o celular como condição
> (`phone`: alcançável, bateria acima/abaixo, carregando, DND, ligação, campainha, rede) e como ação
> (`phoneControl`). Testado: catálogo chegando ao app, `modeAction` (pausar mídia) por atalho com token certo e
> errado. Não testado: as telas, `phoneControl` do shell para o celular, lock/WoL e os gatilhos.

Mais do que acionar modes do shell manualmente pelo app: permitir que **rotinas do celular**
disparem ações e estados diretamente no shell (`lock.activate`, `oledSaver.toggle`, pausar mídia,
mutar áudio do PC, Wake-on-LAN, ativar/desativar um mode ou preset do shell).

- **Integração com o Modes & Routines do sistema (Samsung / Android):** expor ações do shell como
  atalhos/activities acionáveis sem abrir UI (`ShortcutInfo` / `Activity` invisível com
  parâmetros + plugin de ação quando aplicável), para que o usuário monte rotinas nativas do
  celular que alteram o shell (ex.: *"Quando sair de casa / desconectar do Wi-Fi → ativar a
  lockscreen do shell"*).
- **Motor leve + presets prontos dentro do próprio app:** tela com **presets de rotinas prontos**
  (ativáveis em 1 toque, sem depender da marca do aparelho) para os cenários clássicos que
  combinam gatilhos do celular com ações do shell:
  - *Sair de casa* (desconectar do Wi-Fi de casa / perder alcance do link) → bloquear o shell (`lock.activate`, enviado no último fôlego ou via Tailscale).
  - *Hora de dormir* (celular no carregador em horário noturno ou DND de sono ativo) → bloquear o PC + ligar `oledSaver` / pausar mídia.
  - *Chegar em casa* (conectar no Wi-Fi de casa) → enviar Wake-on-LAN para acordar o PC.
  - *Em ligação* → pausar mídia, silenciar notificações ou reduzir volume do PC.

### 5.13 Media Mode de letras no app · **S**

Status: **implemented** (compiles cleanly; not tested on the device).

Botão no `MediaCard` que abre um **Media Mode focado em lyrics** dentro do app em **modo
paisagem** (tela cheia, barras do sistema escondidas, tela acesa): fundo imersivo derivado da
capa/cores da faixa (mesma linguagem visual do Media Mode do shell) e a letra sincronizada vinda
do `LyricsService.qml` (`CustomLyricsStore` / LRCLIB) rolando em destaque pelo relógio local da
sessão (`positionAt(now)`), enviada uma única vez por troca de música (zero polling).

### 5.14 Microfone próprio no ii link · **M**

> **Status (2026-11-14): implementado**, em PCM bruto (48 kHz mono s16le, 768 kbit/s) e não em
> Opus: nenhum codificador a mais no caminho, e o link aguenta. Socket lateral
> `/iilink/1/mic` com token (como a câmera), fonte PipeWire `ii-tools-mic` por `pw-cat`, serviço
> `microphone` com wake lock e Wi-Fi low-latency, tela Mic e pedido do PC por notificação.
> `PhoneMicService` usa o ii link primeiro quando o celular oferece `mic`; scrcpy/DroidCam
> continuam como fallback. Opus fica como opção se algum dia a banda importar.

O celular como microfone do PC pelo próprio link, aposentando o `droidcam-cli` no
`PhoneMicService.qml` (assim como a 4.6 aposentou o DroidCam na câmera).

- **Celular:** captura via `AudioRecord` codificada em Opus de baixa latência (ou PCM na LAN/USB),
  mantida por um foreground service de `microphone` para funcionar com a tela desligada, tanto
  **avulso** (atalho Mic na aba Phone ou tela no app) quanto **junto com a câmera nativa** (4.6).
- **PC:** o helper cria uma fonte virtual do PipeWire (`ii-tools-mic`) que entra direto no
  `PhoneMicService.qml` e no `EasyEffects.qml`. Toggle "Usar o DroidCam" no Settings continua como
  fallback legado.

### 5.15 scrcpy sem fio mais rápido · **S**

Medido em 2026-11-14 (S23 no Wi-Fi de 5 GHz, 80 MHz, PC com power save desligado): ping
PC → celular de 3 ms no melhor caso, 67 ms de média e picos de 317 ms a 1,4 s. É o power save
do rádio do celular. Além disso, o shell limita o scrcpy na rede a 4 Mbit/s, 1600 px e 60 fps,
com H.264.

- **Qualidade:** na rede, 12–16 Mbit/s com `--video-codec=h265` (H.264 se o celular não tiver
  encoder HEVC, verificado uma vez por aparelho); o limite antigo só vale em link fraco (taxa do
  `nmcli` no início da sessão).
- **Rádio acordado:** com uma sessão sem fio aberta, o shell avisa o app pelo link; o app segura
  um `WifiLock` de baixa latência e manda um tráfego mínimo para o rádio não cochilar entre os
  quadros. Só durante a sessão.

### 5.16 Enviar ao PC no topo do menu de compartilhar · **S**

> **Status (2026-11-14): implementado, falta o teste no aparelho.** A ideia original (arrastar de
> qualquer app para uma barra na borda do celular) foi tentada e **descartada**: o Android só
> entrega um arraste a janelas de outros apps quando o app de origem o inicia como global, e o Fotos
> não o faz; a faixa existia e nenhum arraste chegava a ela. No lugar, *Enviar ao PC* é um alvo
> direto (Direct Share: atalho dinâmico com categoria + `share-target` em `shortcuts.xml`) na
> primeira fileira do menu de compartilhar de qualquer app; vindo dali, um arquivo ou imagem vai
> direto para Downloads, sem escolher o destino. Links e textos continuam com a escolha.
> No PC, o popup de transferência ganhou miniatura arrastável (a `FileDragArea` do dock), Copiar
> e Pasta.

### 5.17 Bateria dos periféricos Bluetooth · **S**

> **Status (2026-11-15): implementado.** Com `BLUETOOTH_CONNECT`, o `status` leva
> `peripherals` (`id` = hash do MAC, `name`, `kind`, `level`) dos aparelhos conectados que
> informam bateria; o shell mostra ao lado da bateria do celular (aba Phone, *Settings → ii
> tools*, *Devices*). Receiver de ACL e `BATTERY_LEVEL_CHANGED` só com o link de pé,
> agrupado em 400 ms e no máximo a cada 30 s. Limite do Android: só o nível geral
> (`getBatteryLevel`, por reflexão, liberado); cada fone e o estojo exigem
> `BLUETOOTH_PRIVILEGED`, e relógios Wear OS em geral não informam bateria por Bluetooth.

### 5.18 Avisos de atualização no app · **S**

> **Status (2026-11-15): implementado.** API pública de releases do GitHub com ETag, no
> máximo a cada 12 h, só ao abrir o app ou quando o link sobe (sem WorkManager). Card na
> home, linha no Settings, uma notificação silenciosa por versão, tela com o changelog de
> todas as versões novas (Markdown da release) e *Baixar*, que abre o link direto do APK.

### 5.19 Teclas de volume controlam o PC · **XS**

> **Status (2026-11-15): implementado.** Como o plugin de volume do KDE Connect: uma
> `MediaSession` remota (`VolumeProvider` absoluto, 0–100, 5% por toque) recebe as teclas
> enquanto o link está de pé e o celular não toca nada (`isMusicActive`, modo de áudio
> normal); com a mídia do PC tocando, o provider vai para a sessão do `PcMedia`. Desligado
> por padrão; switch no Áudio e no Settings.

### 5.20 Microfone sem fio sem tocar no celular · **S**

> **Status (2026-11-15): implementado.** O Android 14+ não deixa iniciar um serviço de
> microfone com o app em segundo plano; o pedido do PC virava uma notificação a tocar
> (pelo USB o fallback do scrcpy escondia isso). Testado no S23 (Android 17): o serviço do
> link não herda a permissão de uso depois de o processo reiniciar em segundo plano, e o
> listener de notificações também não; com *Sobrepor a outros apps*, uma activity
> invisível e instantânea (`MicStartActivity`) é aberta em segundo plano, dá o estado de
> primeiro plano e inicia o `MicService` sem acender a tela. Sem a permissão, a
> notificação continua (e também se a activity não abrir em 3 s); o shell mostra "Toque na
> notificação no celular" enquanto espera.

### 5.21 Editar a home · **S**

> **Status (2026-11-15): implementado.** Registro `HomeCards` (id, nome, ícone, removível,
> visível por padrão); a ordem e os ids conhecidos ficam em `SharedPreferences`, e um card
> novo de uma versão futura entra sozinho na posição padrão. *Settings → Editar a tela
> inicial* abre a home em modo de edição: arrastar pela alça (ou toque longo), remover,
> *Adicionar cards*, restaurar o padrão. O card do PC fica fixo.

### 5.22 Vários PCs · **M**

> **Status (2026-11-16): implementado.** O app pareia quantos PCs quiser, sem desparear
> nenhum, e mantém todos conectados ao mesmo tempo (um `PcLink` por PC no `LinkHub`, cada
> um com seu socket, estado e tópicos). A home controla um: o título é o nome do PC, com
> uma seta que abre a lista (bottom sheet) para trocar ou *Parear outro PC*; a mesma lista
> está em Settings. A API pública do `LinkHub` é a do PC selecionado (telas, tiles, widget e
> rotinas não mudaram). O que o celular conta de si (notificações, live, ligações, mídia,
> bateria, DND, tempo de tela, caps) vai para todos; respostas (galeria, arquivos,
> contatos, ícones do launcher, webcam e microfone pedidos pelo PC) voltam para quem
> pediu. Mídia, letras, live activity e wallpaper do PC mostrados no celular são os do
> selecionado, guardados por PC e trocados na hora. Nada mudou no protocolo nem no shell.
> Pareamentos antigos migram sozinhos.

---

## Resumo

| Fase | Features |
| --- | --- |
| 0 Fundação | Protocolo ii link · helper + `IiLinkService` · página `ii tools` no Settings · pareamento · base no app · medição |
| 1 Essenciais | Controle remoto · áudio do PC · deck · controle de ligações · wallpaper · notificações pelo link |
| 2 Baratas | QS tiles e widget · compartilhar para o shell · clipboard com imagens · dados sem ADB · Não Perturbe · Live Updates do PC · screenshot · hotspot e WoL · painel de desempenho |
| 3 Caras e úteis | Monitor (espelhar/estender) · autenticador · presença · áudio das ligações · resto do KDE Connect · touchpad e teclado |
| 4 Nicho | Mesa de desenho · apresentador · miniaturas · câmera própria · galeria (ditado e Health Connect pulados) |
| 5 Dia a dia | Buracos da auditoria (mídia na ligação, filtro por app, pastas e ícones no deck, SMS, medição) · códigos 2FA · avisos à distância · câmera e scanner · deck por app · handoff de mídia · tempo de tela unificado · bateria · wallpaper · localização · histórico do clipboard · brilho e luz noturna · rotinas do celular → shell (com presets) · media mode de letras · microfone próprio · scrcpy sem fio · Enviar ao PC no menu de compartilhar · bateria dos periféricos Bluetooth · avisos de atualização · teclas de volume no PC · microfone sem fio sem toque · editar a home · vários PCs |
