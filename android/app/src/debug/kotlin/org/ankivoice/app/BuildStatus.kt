package org.ankivoice.app

import org.ankivoice.core.fakes.demoCollection

/** Debug builds compile against the :core fakes; release builds cannot see them. */
internal fun buildStatus(): String =
    "AnkiVoice debug build. The AV-007 fakes are available with " +
        "${demoCollection().order.size} demo VoiceQA cards."
