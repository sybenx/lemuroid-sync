#!/bin/sh
# Skip auto-resume when the in-game save is newer than the auto save state, the way Lemuroid does.
# Saves sync from other devices (Syncthing "retro-saves"); without this, RetroArch/DSperate would
# resume an older auto state and hide the newer synced progress. Superseded states are moved, not
# deleted, to /mnt/SDCARD/Saves/states/.superseded/.
#
# Called from spruceOS's launchers (re-add the calls after spruceOS updates):
#   ra_functions.sh  run_retroarch, before the RetroArch command:
#     /mnt/SDCARD/Saves/sync-hooks/skip-stale-resume.sh ra "$ROM_FILE"
#   dsperate_functions.sh  run_dsperate, after prepare_dsperate_rom:
#     /mnt/SDCARD/Saves/sync-hooks/skip-stale-resume.sh ds "$ROM_FILE" "$_rom"

SAVES=/mnt/SDCARD/Saves/saves
STATES=/mnt/SDCARD/Saves/states
STASH=$STATES/.superseded
LOG=/mnt/SDCARD/Saves/sync-hooks/skip-stale-resume.log
TOLERANCE=30

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# mtime in seconds, empty if missing
mtime() { [ -f "$1" ] && date -r "$1" +%s 2>/dev/null; }

stash_if_stale() {
	save="$1"; shift
	save_t=$(mtime "$save")
	[ -n "$save_t" ] || return 0
	for state in "$@"; do
		state_t=$(mtime "$state")
		[ -n "$state_t" ] || continue
		if [ "$save_t" -gt $((state_t + TOLERANCE)) ]; then
			mkdir -p "$STASH"
			stamp=$(date +%Y%m%d-%H%M%S)
			mv "$state" "$STASH/$stamp-$(basename "$state")"
			[ -f "$state.png" ] && mv "$state.png" "$STASH/$stamp-$(basename "$state").png"
			log "save newer than state, skipping resume: $(basename "$save") > $(basename "$state")"
		fi
	done
}

mode="$1"
rom="$(basename "$2")"
base="${rom%.*}"

case "$mode" in
ra)
	# sort_savestates_enable: states live in a per-core folder, so check every core's auto state
	set --
	for s in "$STATES"/*/"$base.state.auto"; do [ -f "$s" ] && set -- "$@" "$s"; done
	[ $# -gt 0 ] && stash_if_stale "$SAVES/$base.srm" "$@"
	;;
ds)
	# DSperate names states by the 4-letter game code in the ROM header (offset 0x0C)
	[ -f "$3" ] || exit 0
	code=$(dd if="$3" bs=1 skip=12 count=4 2>/dev/null | tr -cd 'A-Z0-9')
	[ ${#code} -eq 4 ] || exit 0
	stash_if_stale "$SAVES/dsperate/$base.sav" "$STATES/dsperate/$code.auto.dss"
	;;
esac
exit 0
