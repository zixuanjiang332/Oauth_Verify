package com.craftguard.msverify.storage;

import java.util.List;

public record BatchApplyResult(
        List<VerifiedPlayer> verifiedPlayers,
        List<String> warnings
) {
}
