This overlay is the vcpkg `json-c` port (0.19-20260627, baseline 2026.11.05) plus one patch, `reuse-c-numeric-locale.patch`.

On every parse, `json_tokener_parse_ex` makes a copy of the current locale with `duplocale()` and passes it to `newlocale()`, expecting
`newlocale()` to take ownership. Android's libc does not, so each JSON parse leaked a 16-byte `locale_t`
(`dumpsys meminfo --unreachable` shows them as 16-byte blocks allocated in `json_tokener_parse_ex`).

The patch is the upstream fix, json-c commit `fd92d58` ("Fix memory leak in json_tokener_parse_ex due to duplocale", json-c PR #976),
which creates the "C" numeric locale once and reuses it. It is in json-c master but not in the 0.19-20260627 release.
Only the part of the commit that touches `json_tokener.c` is included.

Remove this overlay once the vcpkg baseline ships a json-c release that contains `fd92d58`.
