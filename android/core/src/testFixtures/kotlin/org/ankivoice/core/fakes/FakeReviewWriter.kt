package org.ankivoice.core.fakes

import org.ankivoice.core.contracts.*

class FakeReviewWriter(
    provider: CardProvider,
    transport: ReviewTransport,
    capabilities: Capabilities = Capabilities(),
) : GuardedReviewWriter(provider, transport, capabilities)
