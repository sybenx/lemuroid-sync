# Lemuroid Sync cores

Cores Lemuroid Sync downloads that LemuroidCores does not ship. The app fetches them from
`https://github.com/sybenx/lemuroid-sync/raw/cores/<abi>/<file>`.

| File | Source | License |
| --- | --- | --- |
| `libtgbdual_libretro_android.so` | libretro buildbot nightly of [tgbdual-libretro](https://github.com/libretro/tgbdual-libretro) at 0392c9c (built 2026-10-19), renamed with the `lib` prefix Lemuroid expects | GPL-2.0 |

TGB Dual runs two Game Boys linked by a cable. Lemuroid Sync uses it for its on-device Link Cable.
