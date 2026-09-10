# Android toolchain contract: an explicit review item

Primary source checked 2026-09-10:
https://developer.android.com/studio/terms
The displayed agreement is dated April 28, 2026.

Section 3.2 restricts using the Android SDK to develop another SDK. Section 3.5
separately states that open-source components are governed by their own open-source
licences. Section 3.1 grants use for applications on compatible Android devices.

The official Android documentation also explicitly supports publishing libraries
for other developers to incorporate into Android applications:
https://developer.android.com/build/publish-library
https://developer.android.com/build/publish-library/prep-lib-release
This is relevant context, not a legal override or blanket permission for every
possible redistribution arrangement.

This project compiles application-linked libraries, a JVM-only geometry core and
an ordinary Android demo; it does not ship Google's Android platform tools,
platform system images or an alternative Android development toolchain. However,
that engineering distinction is NOT, by itself, a verified legal interpretation
of the agreement's SDK wording. A name change from SDK to library would not resolve
a contractual issue. Before commercial SDK distribution, obtain legal review or
clarification for the actual artefacts, toolchain components and distribution
model, including which components fall under section 3.5.

Do not infer that build-only dependencies have no relevant contractual conditions
merely because they do not appear in the AAR. This item is recorded in the machine
readable release blockers in addition to ARCore's independent-value requirement.
No legal permission, blanket prohibition on all third-party Android libraries,
or commercial clearance is claimed here.
