# VoidFlame-Installer

A Paper 26.2 / Java 25 plugin that synchronizes server files from this public GitHub repository. It is designed for a simple workflow: add plugin JARs/configs/world archives under `server-files/`, describe them in `manifest.yml`, run `/vfi check`, then `/vfi update`, and restart Paper.

## Requirements
- Paper (recommended: a recent supported Paper release)
- Java version required by your Paper build
- Outbound HTTPS access to GitHub
- This repository must be public when using the no-token setup

### Active deployment target: VoidFlameMC SMP

The current project direction is **SMP survival**, not Practice/Duels. The active plugin stack is:

- Required: `VoidFlame-Core`, `VoidFlame-Economy`, `VoidFlame-Shop`, `VoidFlame-Sell`, `VoidFlame-AH`, `VoidFlame-Order`, `VoidFlame-Social`, `VoidFlame-Security`, `VoidFlame-Logs`, `VoidFlame-TAB`, `VoidFlame-UI`.
- Permission management: `LuckPerms`; do not install the legacy custom Ranks plugin as a second rank authority.
- Optional staff console: `VoidFlame-Admin`. Do not install both `VoidFlame-Admin` and `VoidFlame-staff-gui` together because they register overlapping staff commands.
- Do not install the legacy Practice/Duels feature plugins (`VoidFlame-Duels`, `VoidFlame-Arenas`, `VoidFlame-Kits`, `VoidFlame-Stats`, `VoidFlame-Ranks`) in the current SMP deployment.
- Keep Minecraft authentication in normal online mode. VoidFlame does not provide `/login` or `/register` accounts.

**Deployment status:** `manifest.yml` is currently empty, and `server-files/plugins/` contains no built plugin JARs. Therefore `/vfi update` currently has no SMP payload to install. Add the actual built JARs and their SHA-256 hashes to the manifest before treating this installer as production-ready. This repository intentionally does not guess or download private-repository artifacts without an authorized distribution path.

# Install
1. Download/build the plugin JAR from GitHub Actions (once the first build finishes), or build locally with `gradle build`.
2. Put `VoidFlame-Installer.jar` into the server's `plugins/` directory.
3. Start Paper once, then stop it.
4. Set `repository.owner`, `repository.name`, and `repository.branch` in `plugins/VoidFlame-Installer/config.yml` if using another repository.
5. Add files to this repo's `server-files/` folder and update `manifest.yml`.
6. Start Paper and run `/vfi check`, review the output, then `/vfi update` and restart.

## Repository layout
```text
manifest.yml
server-files/
  plugins/       # Put plugin JARs here
  configs/       # Put plugin/server config files here
  worlds/        # Put each world as a ZIP archive here
```

### Plugin JARs
Place JARs under `server-files/plugins/`, then add a manifest entry whose `source` is the repository-relative path and whose `target` is the server-relative path, usually `plugins/SomePlugin.jar`.

### Config files
Place config files under `server-files/configs/`, then map each one to the exact server-relative destination. Backups are made before overwriting existing files.

### Worlds
Upload each world as a `.zip` under `server-files/worlds/`. Set `type: world-zip` and `target: world-name`. Worlds are **create-only**: the installer refuses to overwrite an existing world directory. Stop the server before adding/registering a world in Multiverse or another world manager. The installer does not install Multiverse or register worlds automatically.

## Commands
- `/vfi check` — compare manifest entries with installed files and show pending changes.
- `/vfi update` — download, validate, back up, and install listed files.
- `/vfi status` — show repository and last operation state.
- `/vfi rollback` — restore the latest transaction's backups.

Permission: `voidflameinstaller.admin` (granted to server operators by default).

## Manifest format
See `manifest.yml`. `sha256` should contain the SHA-256 hex digest of the exact uploaded file. Blank hashes are allowed for convenience but integrity is then not verified; fill them in before using this for production. The installer never deletes files merely because they are absent from the manifest.

## Safety notes
- Never commit secrets, tokens, player databases, logs, backups, or live server data to a public repository.
- The manifest controls files that will be written to your server. Only update from a repository you control.
- JAR replacement can be blocked by some hosts/operating systems while a file is in use. If a JAR cannot be replaced, it is staged and the command reports the exact path; stop the server and manually move it into place before restarting.
- Existing worlds are never overwritten. World archives are extracted only when the destination world does not exist.
- Rollback restores backed-up files from the most recent successful update. Keep your own server backups as well.

## Build
```bash
gradle build
```
The output JAR is `build/libs/VoidFlame-Installer-1.0.0.jar`.
