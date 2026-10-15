# Third-party notices — ai.graine:inapp-android-rtc

This module depends on **WebRTC** (Google's libwebrtc), used as a prebuilt,
symbol-prefixed Android library: `io.github.webrtc-sdk:android-prefixed-stripped`
version `150.7871.01` (its Java classes live in `livekit.org.webrtc`). The
module itself contains none of its code; your app's build downloads the
library from Maven Central and packages its native code for each ABI.

Include the notices below in your app's open-source licences screen.

## WebRTC

Licence: BSD 3-Clause (https://spdx.org/licenses/BSD-3-Clause.html), with
Google's additional patent grant below. The Android packaging is published
under the same BSD 3-Clause licence.

```
Copyright (c) 2011, The WebRTC project authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of Google nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

```
Additional IP Rights Grant (Patents)

"This implementation" means the copyrightable works distributed by
Google as part of the WebRTC code package.

Google hereby grants to you a perpetual, worldwide, non-exclusive,
no-charge, irrevocable (except as stated in this section) patent
license to make, have made, use, offer to sell, sell, import,
transfer, and otherwise run, modify and propagate the contents of this
implementation of the WebRTC code package, where such license applies
only to those patent claims, both currently owned by Google and
acquired in the future, licensable by Google that are necessarily
infringed by this implementation of the WebRTC code package. This
grant does not include claims that would be infringed only as a
consequence of further modification of this implementation. If you or
your agent or exclusive licensee institute or order or agree to the
institution of patent litigation against any entity (including a
cross-claim or counterclaim in a lawsuit) alleging that this
implementation of the WebRTC code package or any code incorporated
within this implementation of the WebRTC code package constitutes
direct or contributory patent infringement, or inducement of patent
infringement, then any patent rights granted to you under this License
for this implementation of the WebRTC code package shall terminate as
of the date such litigation is filed.
```

## Code WebRTC includes

WebRTC's native library is built with code from these projects, each under
the licence named (the full texts ship with WebRTC's sources):

| Project | Licence |
|---|---|
| BoringSSL | OpenSSL and SSLeay licences, ISC, Apache-2.0 (https://spdx.org/licenses/OpenSSL.html, https://spdx.org/licenses/ISC.html, https://spdx.org/licenses/Apache-2.0.html) |
| Opus | BSD-3-Clause |
| libsrtp | BSD-3-Clause |
| Abseil | Apache-2.0 |
| usrsctp | BSD-3-Clause |
| libyuv | BSD-3-Clause |
| RNNoise | BSD-3-Clause |
| PFFFT | BSD-like (the FFTPACK licence) |
| Ooura FFT | permissive (free use with its notice) |
| crc32c | BSD-3-Clause |
| Protocol Buffers (lite) | BSD-3-Clause |

The stripped build carries no software video codecs.
