# VoidFlame-Installer

A Paper 26.2 / Java 25 plugin that synchronizes files for the VoidFlameMC Practice/Duels server from this public GitHub repository. It is designed for a simple workflow: add plugin JARs/configs/world archives under `server-files/`, describe them in `manifest.yml`, run `/vfi check`, then `/vfi update`, and restart Paper.

## Requirements
- Paper 26.2
- Java 25
- Outbound HTTPS access to GitHub
- This repository must be public when using the no-token setup

## Install
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
