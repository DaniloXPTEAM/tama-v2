# Provider endpoint host checks

Run with a JDK and `kotlinc` on `PATH`:

```sh
bash tests/host_android/provider_endpoints/run.sh
```

No network, real key, account or Android SDK is used. The test compiles the actual
`ProviderEndpoints.kt` and `OwnKeyPresets.kt` with fake Android/catalogue/model
classes, extracts the actual `ProviderInstance` class and two actual statements
from the OpenAI branch of `ProviderFactory`, and evaluates them together. The
catalogue entries are fictitious fixtures carrying the public endpoint strings;
the script also checks those strings and key hints against the production JSON
and checks a few form links statically, but the production catalogue parser and
Compose are not run. The factory as a whole, provider transport, persistence,
error handling and Android are not run. It also executes the real pure
`KeyDestinationGuard`: recognized and unknown prefixes, URL validation, refusal,
explicit process-local confirmation bound to the exact key and route, and the
normal-identity bypass. Some form, refresh and chat wiring is checked only as text.
It does not exercise the actual UI, coroutines, HTTP, fallback, persistence, process
restart or all possible transports. The checks do not prove which endpoint or error
occurred in the older Poco build.

The empty string cases intentionally differ from the normal UI save path: the
form stores a blank value as `null`. Legacy or externally imported blank strings
may behave differently depending on the `/v1` setting; their occurrence on the
phone is not established.
