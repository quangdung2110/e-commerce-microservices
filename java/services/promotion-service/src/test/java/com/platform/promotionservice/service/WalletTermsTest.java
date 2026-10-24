package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.rule.ConditionNode;
import com.platform.promotionservice.domain.rule.LogicalNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WalletTermsTest {
    @Test void hidesAudienceIdsAndPreservesLogicalRelationship() {
        String text = WalletTerms.describe(LogicalNode.or(
                ConditionNode.of("IN", "user.id", List.of("secret-other-user")),
                ConditionNode.of("GREATER_THAN_OR_EQUAL", "cart.total_amount", 100000)));
        assertTrue(text.contains("Một trong"));
        assertTrue(text.contains("100.000"));
        assertFalse(text.contains("secret-other-user"));
        assertFalse(text.contains("user.id"));
    }
}
