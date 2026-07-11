# Press to Talk

Ferramenta local de **press-to-talk** para Linux: prime **Ctrl+M** para começar a gravar, mantém **Ctrl** premido enquanto falas (podes soltar o M), e solta **Ctrl** para transcrever com [faster-whisper](https://github.com/SYSTRAN/faster-whisper) e copiar o texto para a área de transferência.

Funciona em segundo plano com um ícone na bandeja do sistema e um pequeno popup de estado no canto inferior direito do ecrã.

## O que faz

- **Gravação por atalho**: Ctrl+M para iniciar; mantém Ctrl premido (M pode ser libertado)
- **Transcrição local**: modelo Whisper (por defeito `large-v3-turbo`) na GPU CUDA
- **Clipboard**: o texto transcrito é copiado automaticamente com `xclip`
- **Deteção de idioma**: português e inglês (auto-detect)
- **Supressão de teclas no X11**: Ctrl+M não chega a outras aplicações enquanto o serviço está ativo
- **Instância única**: se arrancares outra vez, a instância anterior é terminada

## Requisitos

- Linux com X11 (testado em ambiente tipo Linux Mint / Cinnamon)
- Python 3.12+
- [uv](https://docs.astral.sh/uv/) para gestão de dependências
- NVIDIA GPU com CUDA (recomendado; também podes usar `--device cpu`)
- Pacotes de sistema:
  - `xclip`
  - `python3-gi` (GTK 3)
  - `gir1.2-ayatanaappindicator3-0.1` (opcional, para ícone na bandeja Ayatana)

Exemplo no Ubuntu / Linux Mint:

```bash
sudo apt install xclip python3-gi gir1.2-ayatanaappindicator3-0.1
```

## Instalação

```bash
git clone git@github.com:FeatureSpitter/press-to-talk.git
cd press-to-talk

uv venv --python /usr/bin/python3 --system-site-packages
uv sync
```

O venv precisa de `--system-site-packages` para aceder aos bindings GTK do sistema.

### Teste rápido (carregar o modelo)

```bash
uv run press_to_talk.py --check
```

## Utilização

Arranca a aplicação:

```bash
uv run press_to_talk.py
```

Ou, se já tiveres o venv criado:

```bash
./launch.sh
```

Depois:

1. Espera o modelo carregar (popup “Loading model...”).
2. Prime **Ctrl+M** para começar a gravar.
3. Mantém **Ctrl** premido enquanto falas (podes soltar o M).
4. Solta **Ctrl** → transcreve e copia para o clipboard.
5. Cola com Ctrl+V onde quiseres.

Outros atalhos:

- **Ctrl+Q** (com Ctrl premido): sair da aplicação
- **Clique direito no ícone da bandeja** → Quit

Se não houver fala detetável (microfone mudo, níveis muito baixos, etc.), o popup mostra **“No speech detected”**.

### Opções úteis

```bash
uv run press_to_talk.py --model large-v3-turbo --device cuda --compute-type float16
uv run press_to_talk.py --language pt          # forçar português
uv run press_to_talk.py --device cpu           # sem GPU
```

Para desativar a supressão de teclas X11:

```bash
PTT_NO_GRAB=1 uv run press_to_talk.py
```

## Linux Mint: menu Iniciar, favoritos e arranque automático

O repositório inclui `press-to-talk.desktop` e `launch.sh`.

### 1. Ajustar o ficheiro `.desktop`

Edita `press-to-talk.desktop` e substitui o caminho pelo teu:

```ini
Exec=/caminho/para/press-to-talk/launch.sh
Path=/caminho/para/press-to-talk
```

Exemplo:

```ini
Exec=/home/milhas/projectos/press-to-talk/launch.sh
Path=/home/milhas/projectos/press-to-talk
```

### 2. Aparecer no menu Iniciar

```bash
mkdir -p ~/.local/share/applications
cp press-to-talk.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications/
```

No Linux Mint (Cinnamon): abre o **Menu Iniciar**, procura **Press to Talk** e:

- **Clicar com o botão direito** → *Add to favorites* / *Adicionar aos favoritos*
- Ou arrasta o ícone para a barra de favoritos

### 3. Arrancar ao iniciar sessão (opcional)

```bash
mkdir -p ~/.config/autostart
cp press-to-talk.desktop ~/.config/autostart/
```

Se preferires não arrancar automaticamente, deixa `X-GNOME-Autostart-enabled=false` no `.desktop` (valor por defeito no repositório).

### 4. Atalho de teclado no Mint

O atalho principal (**Ctrl+M** para iniciar, **soltar Ctrl** para parar) já está integrado na aplicação (não precisas de o configurar no sistema).

Se quiseres um atalho do sistema só para **abrir** a app (por exemplo **Super+Shift+V**):

1. **Definições do sistema** → **Teclado** → **Atalhos** → **Atalhos personalizados**
2. Adiciona um novo atalho:
   - **Nome**: Press to Talk
   - **Comando**: `/home/milhas/projectos/press-to-talk/launch.sh`
   - **Tecla**: a combinação que quiseres

Isto lança o serviço; a gravação continua a ser **Ctrl+M** para iniciar e **soltar Ctrl** para parar.

## Microfone

Confirma no **Definições → Som → Entrada** (ou `pavucontrol`) que o microfone correto está selecionado e que o volume não está muito baixo ou mudo. Se a app gravar silêncio, verás **“No speech detected”** em vez de texto no clipboard.

## Testes

```bash
uv run pytest test_press_to_talk.py -v
```

## Licença

Projeto pessoal / utilitário local. Usa por tua conta e risco.
