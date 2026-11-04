package com.bol.app.data.oss

/**
 * Static catalog of every third-party component shipped in Heed's APK:
 * libraries, and the three model files bundled in its assets.
 *
 * Maintenance: when a new dependency is added to gradle/libs.versions.toml
 * (or vendored under app/libs/), append it here. License compliance for
 * Apache 2.0 + MIT is satisfied by surfacing the entry on the OSS
 * Licenses screen with its license text reachable in the same screen.
 */
enum class OssLicenseType(val displayName: String) {
    APACHE_2_0("Apache License 2.0"),
    MIT("MIT License"),
    GEMMA("Gemma Terms of Use"),
    OFL("SIL Open Font License 1.1"),
    PUBLIC_DOMAIN("Public domain"),
}

data class OssComponent(
    val name: String,
    val version: String,
    val author: String,
    val license: OssLicenseType,
    /** Brief one-line description of what this component does in Heed. */
    val role: String,
    val url: String,
)

object OssLicenses {

    /**
     * Bundled libraries — everything that ships in the release APK as Kotlin/Java
     * bytecode or native code. Versions track gradle/libs.versions.toml.
     */
    val libraries: List<OssComponent> = listOf(
        // --- AI runtime ---
        OssComponent(
            name = "LiteRT-LM",
            version = "0.15.0",
            author = "Google LLC",
            license = OssLicenseType.APACHE_2_0,
            role = "On-device runtime for the task model",
            url = "https://github.com/google-ai-edge/LiteRT-LM",
        ),
        OssComponent(
            name = "LiteRT",
            version = "2.2.0",
            author = "Google LLC",
            license = OssLicenseType.APACHE_2_0,
            role = "On-device runtime for voice to text",
            url = "https://github.com/google-ai-edge/LiteRT",
        ),
        OssComponent(
            name = "ONNX Runtime",
            version = "1.23.2",
            author = "Microsoft Corporation",
            license = OssLicenseType.MIT,
            role = "On-device runtime for the search model",
            url = "https://github.com/microsoft/onnxruntime",
        ),
        OssComponent(
            name = "AndroidX SQLite (bundled)",
            version = "2.5.2",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Its own copy of SQLite, for the search index",
            url = "https://developer.android.com/jetpack/androidx/releases/sqlite",
        ),
        OssComponent(
            name = "SQLite",
            version = "3.x (inside AndroidX SQLite)",
            author = "D. Richard Hipp and the SQLite developers",
            license = OssLicenseType.PUBLIC_DOMAIN,
            role = "Full-text search (FTS5) for the search index",
            url = "https://sqlite.org/copyright.html",
        ),
        // --- Android & UI ---
        OssComponent(
            name = "Jetpack Compose",
            version = "BOM 2024.09.00",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Modern declarative UI toolkit",
            url = "https://developer.android.com/jetpack/compose",
        ),
        OssComponent(
            name = "AndroidX Core, Lifecycle, Activity, Navigation",
            version = "various",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Android platform support libraries",
            url = "https://developer.android.com/jetpack/androidx",
        ),
        OssComponent(
            name = "AndroidX Glance",
            version = "1.1.1",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Compose-based home-screen widgets",
            url = "https://developer.android.com/jetpack/androidx/releases/glance",
        ),
        OssComponent(
            name = "AndroidX Room",
            version = "2.7.1",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "SQLite ORM for the local database",
            url = "https://developer.android.com/jetpack/androidx/releases/room",
        ),
        OssComponent(
            name = "AndroidX DataStore",
            version = "1.1.7",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Preferences storage",
            url = "https://developer.android.com/jetpack/androidx/releases/datastore",
        ),
        OssComponent(
            name = "AndroidX WorkManager",
            version = "2.10.1",
            author = "The Android Open Source Project",
            license = OssLicenseType.APACHE_2_0,
            role = "Background work scheduling",
            url = "https://developer.android.com/jetpack/androidx/releases/work",
        ),
        OssComponent(
            name = "Material Icons Extended",
            version = "Compose BOM",
            author = "Google LLC",
            license = OssLicenseType.APACHE_2_0,
            role = "Material Design icon set",
            url = "https://fonts.google.com/icons",
        ),

        // --- Kotlin ---
        OssComponent(
            name = "Kotlin",
            version = "2.0.21",
            author = "JetBrains s.r.o. and Kotlin contributors",
            license = OssLicenseType.APACHE_2_0,
            role = "Programming language + standard library",
            url = "https://kotlinlang.org",
        ),
        OssComponent(
            name = "kotlinx.coroutines",
            version = "1.10.1",
            author = "JetBrains s.r.o. and Kotlin contributors",
            license = OssLicenseType.APACHE_2_0,
            role = "Asynchronous programming",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        OssComponent(
            name = "kotlinx.serialization",
            version = "1.7.3",
            author = "JetBrains s.r.o. and Kotlin contributors",
            license = OssLicenseType.APACHE_2_0,
            role = "JSON encoding/decoding",
            url = "https://github.com/Kotlin/kotlinx.serialization",
        ),

        // --- DI ---
        OssComponent(
            name = "Koin",
            version = "4.0.4",
            author = "Insert Koin Authors",
            license = OssLicenseType.APACHE_2_0,
            role = "Dependency injection",
            url = "https://insert-koin.io",
        ),
    )

    /** Fonts bundled in res/font (the app has no internet to fetch them). */
    val fonts: List<OssComponent> = listOf(
        OssComponent(
            name = "Inter",
            version = "variable (opsz, wght)",
            author = "The Inter Project Authors",
            license = OssLicenseType.OFL,
            role = "Headings and body text",
            url = "https://github.com/rsms/inter",
        ),
        OssComponent(
            name = "JetBrains Mono",
            version = "variable (wght)",
            author = "The JetBrains Mono Project Authors",
            license = OssLicenseType.OFL,
            role = "Small caps labels (dates, section names, tabs)",
            url = "https://github.com/JetBrains/JetBrainsMono",
        ),
    )

    /** Model files bundled in the APK's assets, subject to their authors' licenses. */
    val models: List<OssComponent> = listOf(
        OssComponent(
            name = "Qwen3-0.6B (fine-tuned for Heed)",
            version = "Qwen/Qwen3-0.6B-Base",
            author = "Alibaba Cloud Qwen team",
            license = OssLicenseType.APACHE_2_0,
            role = "On-device task model",
            url = "https://huggingface.co/Qwen/Qwen3-0.6B-Base",
        ),
        OssComponent(
            name = "Granite Speech 5.0 470M TurboCTC (LiteRT build)",
            version = "ibm-granite/granite-speech-5.0-470m-turboctc",
            author = "IBM",
            license = OssLicenseType.APACHE_2_0,
            role = "On-device voice to text",
            url = "https://huggingface.co/ibm-granite/granite-speech-5.0-470m-turboctc",
        ),
        OssComponent(
            name = "EmbeddingGemma 300M (4-bit ONNX, onnx-community export)",
            version = "onnx-community/embeddinggemma-300m-ONNX (model_q4)",
            author = "Google LLC",
            license = OssLicenseType.GEMMA,
            role = "On-device search",
            url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX",
        ),
    )

    /** Shown for the Gemma Terms of Use entry: the notice the terms ask for, and where the full terms live. */
    val GEMMA_NOTICE: String = """
Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms

Use of Gemma is also subject to the Gemma Prohibited Use Policy: ai.google.dev/gemma/prohibited_use_policy

Heed ships EmbeddingGemma 300M as the 4-bit ONNX files published by onnx-community, unchanged. It turns your tasks, notes and search queries into vectors on this phone; nothing is sent anywhere.
""".trimIndent()

    /** SIL Open Font License 1.1 — the text that ships with Inter and JetBrains Mono (Copyright 2020 The Inter Project Authors; Copyright 2020 The JetBrains Mono Project Authors). */
    val OFL_TEXT: String = """
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
""".trim()

    /** SQLite's own dedication, from sqlite.org/copyright.html (no license text to reproduce). */
    val SQLITE_NOTICE: String = """
The author disclaims copyright to this source code. In place of a legal notice, here is a blessing:

May you do good and not evil.
May you find forgiveness for yourself and forgive others.
May you share freely, never taking more than you give.
""".trimIndent()

    /** Apache License 2.0 — full text. Reproduced once and referenced by every Apache-licensed entry. */
    val APACHE_2_0_TEXT: String = """
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for describing the origin of the Work and
      reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Support. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or support.

   END OF TERMS AND CONDITIONS
""".trimIndent()

    /**
     * MIT License — generic text. The specific copyright line for each
     * MIT-licensed component is shown alongside its entry above.
     */
    val MIT_TEXT: String = """
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""".trimIndent()
}
