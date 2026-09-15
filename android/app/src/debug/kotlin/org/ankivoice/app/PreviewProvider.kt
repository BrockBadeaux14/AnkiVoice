package org.ankivoice.app

import org.ankivoice.core.contracts.CardProvider
import org.ankivoice.core.fakes.FakeCardProvider
import org.ankivoice.core.fakes.demoCollection

internal fun previewCardProvider(): CardProvider = FakeCardProvider(demoCollection())
internal const val PREVIEW_DESCRIPTION = "Preview uses sample cards. Your AnkiDroid cards are not reviewed."
