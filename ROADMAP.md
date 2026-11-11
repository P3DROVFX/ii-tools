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

- **Shell:** toda mudança vai primeiro para a branch **`feat/ii-tools`** do repo `ii-vynx`;
  merge em `dev` só quando a fase estiver testada.
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

Substitui o ADB do `PhoneCallService`.

- Estado da chamada via `TelephonyCallback`; atender e recusar via `TelecomManager`
  (`ANSWER_PHONE_CALLS`); discar do PC (`CALL_PHONE`); mutar o microfone.
- O shell continua com o card de ligação que já tem, agora com botões que funcionam sem ADB e
  com o fim da chamada detectado de verdade.
- O áudio da chamada no PC fica para a Fase 3.4.

### 1.5 Wallpaper do celular · **S**

- O app aparece como **alvo de compartilhamento** ("Definir no shell") e também tem um seletor.
- Envia o arquivo original pelo link; o shell salva na pasta de wallpapers e chama
  `wallpapers.apply` ou `wallpapers.applyLockscreen`. As cores se regeneram sozinhas.
- Bônus: navegar pela pasta de wallpapers do PC com miniaturas (geradas e guardadas em cache sob
  demanda).

### 1.6 Notificações pelo link · **M**

O app já tem o `NotificationListenerService`; este é o primeiro pedaço do KDE Connect a substituir.

- Espelho de notificações com ícone, imagem grande, ações e resposta rápida.
- **Live updates** passam a ir pelo link em vez do truque da notificação. O marcador `iilive1:`
  continua como fallback quando o link não está ativo.
- Dispensar no PC dispensa no celular, e o contrário também.
- Toggle na página `ii tools`; com ele ligado, o shell ignora as cópias do KDE Connect para não
  duplicar.

---

## Fase 2 — Baratas

### 2.1 Quick Settings tiles e widget · **XS**

Tiles do Android para bloquear o PC, AOD, media mode e um preset favorito. Widget com uma mini
grade do deck.

### 2.2 Compartilhar para o shell · **S**

Pelo menu de compartilhamento do Android: link abre no navegador do PC ou vai para o chat de IA;
texto vira nota (`NotesService`), lembrete (`RemindersService`) ou tarefa (`Todo`); foto vai como
anexo da IA; arquivo vai para o `FileShelf`.

### 2.3 Clipboard com imagens · **S**

Texto e imagem nos dois sentidos, integrado ao `Cliphist`. Só sincroniza o que o usuário copia
com o app em primeiro plano ou por ação explícita (o Android restringe ler o clipboard em
segundo plano).

### 2.4 Dados do celular sem ADB · **S**

- Próximo alarme (`AlarmManager.getNextAlarmClock`) para o `PhoneAlarmService`.
- Contatos com foto (`ContactsContract`) para o `PhoneContactsService`.
- Bateria detalhada (temperatura, saúde, carregamento rápido), tipo de rede e sinal.

### 2.5 Não Perturbe nos dois sentidos · **S**

Modes do shell (game mode, foco, bedtime) ligam o Não Perturbe do celular, e o Não Perturbe do
celular pode ativar um mode no shell. Mapeamento configurável na página `ii tools`.

### 2.6 Live Updates do PC no celular · **S**

O caminho inverso do relay: gravação em andamento, timers (`TimerService`), alarmes
(`AlarmService`) e progressos (`ProgressService`) viram Live Updates (`ProgressStyle`) na tela
de bloqueio do celular, com botões (parar a gravação, pausar o timer).

### 2.7 Screenshot entregue · **XS**

Print tirado no PC aparece como notificação no celular, com a imagem. Print tirado no celular
aparece no popup de preview do shell (`ScreenshotOverlay`).

### 2.8 Hotspot e Wake-on-LAN · **XS**

Ligar o hotspot do celular pelo shell (`Hotspot.qml`); acordar o PC pelo app (magic packet).

### 2.9 Painel de desempenho · **S**

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

### 3.2 Celular como autenticador · **L**

Aprovar pedidos do polkit (`PolkitService`), `sudo` (`AskpassService`) e desbloquear o lockscreen
com a biometria do celular (`BiometricPrompt` + chave no Android Keystore assinando um desafio
do shell). Precisa de desenho de segurança cuidadoso: desafio de uso único, expiração curta,
sempre opcional, nunca substitui a senha como fallback.

### 3.3 Presença · **M**

Celular se afasta (link cai ou BLE fraco) → o shell bloqueia. Celular volta → o shell oferece
desbloqueio rápido (com a 3.2). Usa o advertising BLE só quando a feature está ligada.

### 3.4 Áudio das ligações no PC · **XL** (começa com spike)

O Android não deixa app de terceiros capturar o áudio de uma chamada. O único caminho é o PC
virar um fone Bluetooth (perfil HFP, papel HF). **Spike primeiro:** testar o suporte a HFP-HF
no PipeWire / oFono da distro. Se funcionar, o shell roteia microfone e alto-falante e o app
(com a 1.4) cuida do controle.

### 3.5 Substituir o resto do KDE Connect · **L**

Uma feature por vez, com o KDE Connect como fallback enquanto o app não estiver pareado:
SMS (ler e responder), controle de mídia do PC no celular (`MediaSession`), achar o celular,
envio de arquivos, ping, compartilhamento de URL. Cada uma vira um toggle na página `ii tools`.

### 3.6 Touchpad e teclado · **M**

Celular como touchpad (gestos de vários dedos mapeados para o Hyprland) e teclado, via uinput.
Substitui o mousepad e o remotekeyboard do KDE Connect.

---

## Fase 4 — Nicho

### 4.1 Mesa de desenho para o LiveDraw · **M**

Desenhar no celular (com pressão, se houver caneta) e o traço aparecer no `liveDraw` do PC.

### 4.2 Apresentação e teleprompter · **S**

Passador de slides (setas, laser na tela) e controle do `Teleprompter` (rolagem, velocidade).

### 4.3 Ditado pelo celular · **M**

Microfone do celular alimentando o `DictationService` do shell.

### 4.4 Health Connect · **M**

Passos, sono e batimentos para o `HealthService` do shell.

### 4.5 Miniaturas ao vivo dos workspaces · **M**

A tela de workspaces da 1.1 ganha miniaturas reais das janelas, capturadas sob demanda e com
baixa resolução.

### 4.6 Câmera própria · **L**

Substituir o DroidCam por um stream de câmera do próprio app (CameraX + `MediaCodec`), integrado
ao `PhoneCameraService`. Só vale se o DroidCam virar um problema.

---

## Resumo

| Fase | Features |
| --- | --- |
| 0 Fundação | Protocolo ii link · helper + `IiLinkService` · página `ii tools` no Settings · pareamento · base no app · medição |
| 1 Essenciais | Controle remoto · áudio do PC · deck · controle de ligações · wallpaper · notificações pelo link |
| 2 Baratas | QS tiles e widget · compartilhar para o shell · clipboard com imagens · dados sem ADB · Não Perturbe · Live Updates do PC · screenshot · hotspot e WoL · painel de desempenho |
| 3 Caras e úteis | Monitor (espelhar/estender) · autenticador · presença · áudio das ligações · resto do KDE Connect · touchpad e teclado |
| 4 Nicho | Mesa de desenho · apresentação · ditado · Health Connect · miniaturas · câmera própria |
