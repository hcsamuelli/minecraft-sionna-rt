# Commands

Every command you need, grouped by where you type it. See [README.md](README.md) for how it all works.

## Join a server (your computer, Prism Launcher)


1. In Prism, click **Add Instance**, pick Minecraft **26.3**, set Mod loader to **Fabric**, and click OK. Prism downloads Java 25 on its own. If it asks, open the instance's Edit → Settings → Java and click Download Java.
2. Right-click the instance → Edit → Mods → **Download mods**, search for "Fabric API", select it, and click OK.
3. Copy the radio mod to your computer:

   ```bash
   ```

   Drag the file onto the instance's Mods page, or use **Add file**.
4. Launch the instance, go to Multiplayer → Add Server, and enter the address.

## In game

| Command | What it does |
|---|---|
| `/give @s mc_sionna:transmitter` | Get a Radio Transmitter block. Both blocks are also in the Functional Blocks creative tab |
| `/give @s mc_sionna:receiver` | Get a Radio Receiver block |
| `/sionna run` | Trace paths for every transmitter and receiver within `radius` blocks of you |
| `/sionna clear` | Remove all drawn rays and labels |
| `/sionna settings` | Show the current settings |
| `/sionna set <key> <value>` | Change a setting. Anyone can, except `url`, which needs op |

Placing or breaking a radio block re-runs the simulation on its own (turn that off with `/sionna set auto_run false`).

Useful settings:

```
/sionna set frequency_ghz 28
/sionna set max_depth 6
/sionna set radius 64
/sionna set max_drawn_paths 200
/sionna set thickness_per_block_m 1.0
/sionna set diffraction true
```

The README lists every setting with its default.

## Run the server (on the GPU machine, from the project folder)

Both programs run in `tmux` sessions, so they keep going after you log out.

| Command | What it does |
|---|---|
| `tmux new-session -d -s sionna "uv run mc-sionna-server"` | Start the Sionna service (port 8765, this machine only) |
| `tmux new-session -d -s mc ./server/start.sh` | Start the Minecraft server (port 25565) |
| `tmux ls` | See what's running |
| `tmux attach -t mc` | Open the server console to type commands. Leave it running with Ctrl-b then d |
| `tmux send-keys -t mc "stop" Enter` | Stop the Minecraft server cleanly |
| `tmux kill-session -t sionna` | Stop the Sionna service |
| `curl localhost:8765/health` | Check the Sionna service is up |
| `tail -f server/logs/latest.log` | Watch the server log |

Server console commands, sent with `tmux send-keys -t mc "<command>" Enter` or typed after `tmux attach -t mc`:

| Command | What it does |
|---|---|
| `op <name>` | Give a player op, which they need to change `url` |
| `whitelist on` / `whitelist off` | Only let listed players in, or let anyone in (off by default) |
| `whitelist add <name>` | Add a player to the allowlist |
| `list` | See who's online |

If players can't connect from other computers, open the port in the firewall:

```bash
sudo firewall-cmd --add-port=25565/tcp --permanent && sudo firewall-cmd --reload
```

## Exports

Each run writes `exports/<timestamp>/`, holding `scene.xml` + `meshes/`, `channels.npz`, `channels.mat`, `paths.json` and `metadata.json`.

```bash
ls -t exports | head
```

Load a saved scene in Sionna:

```bash
uv run python -c "from sionna.rt import load_scene; s = load_scene('exports/<timestamp>/scene.xml'); print(s.objects.keys())"
```

## Development

| Command | What it does |
|---|---|
| `uv run pytest` | Python tests (meshing and a full Sionna run on a small fake world) |
| `uv run ruff check && uv run ty check src` | Lint and type check |
| `cd mod && JAVA_HOME=../.tools/jdk-25.0.5+8 ./gradlew build` | Build the mod jar into `mod/build/libs/` |
| `cp mod/build/libs/mc-sionna-0.1.0.jar server/mods/` | Put a new build on a server, then restart it |
| `cd mod && JAVA_HOME=../.tools/jdk-25.0.5+8 ./gradlew runServer --args=nogui` | Offline test server (flat world, remote console on). Stop a server first: both use port 25565 |
| `uv run python scripts/e2e.py` | End-to-end test against that test server |
