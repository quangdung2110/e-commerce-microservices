package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.BalanceLedgerPort;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.platform.paymentservice.application.port.out.ReconciliationPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BalanceReconciliationService {
    private final ReconciliationPort runs;
    private final ProviderBalancePort provider;
    private final BalanceLedgerPort ledger;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final ObjectMapper json;

    public BalanceReconciliationService(ReconciliationPort runs, ProviderBalancePort provider,
                                        BalanceLedgerPort ledger, IdGeneratorPort ids,
                                        ClockPort clock, ObjectMapper json) {
        this.runs = Objects.requireNonNull(runs);
        this.provider = Objects.requireNonNull(provider);
        this.ledger = Objects.requireNonNull(ledger);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
    }

    public ReconciliationService.Outcome run(Instant windowFrom, Instant windowTo,
                                             boolean frozen, int limit) {
        if (windowFrom == null || windowTo == null || !windowFrom.isBefore(windowTo))
            throw new IllegalArgumentException("invalid reconciliation window");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        var optional = runs.start("BALANCE_LEDGER", windowFrom, windowTo, frozen, clock.now());
        if (optional.isEmpty()) return ReconciliationService.Outcome.ALREADY_RUNNING;
        var run = optional.get();
        MutableCounts counts = new MutableCounts();
        try {
            if (run.autoCorrectionFrozen()) throw new IllegalStateException("AUTO_CORRECTION_FROZEN");
            String cursor = null;
            java.util.Set<String> cursors = new java.util.HashSet<>();
            while (true) {
                ProviderBalancePort.Page page = provider.list(windowFrom, windowTo, cursor, Math.min(limit, 100));
                for (ProviderBalancePort.Entry entry : page.entries())
                    runs.owned(run.id(), () -> {
                        if (runs.currentFreeze(run.autoCorrectionFrozen()))
                            throw new IllegalStateException("AUTO_CORRECTION_FROZEN");
                        ingest(entry, counts);
                        return null;
                    });
                cursor = page.nextCursor();
                if (!page.hasMore()) break;
                if (cursor == null || cursor.isBlank() || !cursors.add(cursor))
                    throw new IllegalStateException("BALANCE_CURSOR_NOT_ADVANCING");
            }
            runs.complete(run.id(), cursor, counts.snapshot(), clock.now());
            return ReconciliationService.Outcome.COMPLETED;
        } catch (RuntimeException failure) {
            runs.fail(run.id(), failure.getMessage(), clock.now());
            return ReconciliationService.Outcome.FAILED;
        }
    }

    private void ingest(ProviderBalancePort.Entry entry, MutableCounts counts) {
        counts.scanned++;
        if (!"VND".equals(entry.currency()))
            throw new IllegalStateException("BALANCE_CURRENCY_MISMATCH:" + entry.id());
        BalanceLedgerPort.Owner owner = requiresOwner(entry.type())
                ? ledger.resolve(entry.sourceId()).orElseThrow(() ->
                new IllegalStateException("BALANCE_SOURCE_UNMAPPED:" + entry.id()))
                : ledger.resolve(entry.sourceId()).orElse(new BalanceLedgerPort.Owner(null, null, null));
        if (Math.subtractExact(entry.amountMinor(), entry.feeMinor()) != entry.netMinor())
            throw new IllegalStateException("BALANCE_NET_MISMATCH:" + entry.id());
        String type = journalType(entry.type(), entry.amountMinor(), owner);
        if (("CAPTURE".equals(type) || "REFUND_REVERSAL".equals(type)) && entry.amountMinor() < 0
                || ("REFUND".equals(type) || "PAYOUT_SETTLEMENT".equals(type)) && entry.amountMinor() > 0)
            throw new IllegalStateException("BALANCE_SIGN_MISMATCH:" + entry.id());
        long amount = Math.abs(entry.amountMinor());
        if (amount == 0) throw new IllegalStateException("BALANCE_ZERO_AMOUNT:" + entry.id());
        Accounts accounts = accounts(type);
        if ("PROCESSING_FEE".equals(type) && entry.amountMinor() > 0)
            accounts = new Accounts(accounts.credit(), accounts.debit());
        String sourceType = switch (type) {
            case "CAPTURE" -> "CHARGE";
            case "REFUND", "REFUND_REVERSAL", "DISPUTE_WITHDRAWAL", "DISPUTE_REINSTATEMENT" -> type;
            default -> "BALANCE_TRANSACTION";
        };
        String sourceId = switch (type) {
            case "CAPTURE", "REFUND" -> entry.sourceId();
            case "REFUND_REVERSAL" -> entry.sourceId() + ":reversal";
            case "DISPUTE_WITHDRAWAL", "DISPUTE_REINSTATEMENT" -> owner.disputeId();
            default -> entry.id();
        };
        ledger.post(journal(entry, owner, type, sourceType, sourceId, entry.id(), amount, accounts));
        addTotal(type, "PROCESSING_FEE".equals(type) && entry.amountMinor() > 0 ? -amount : amount, counts);

        long fee = Math.abs(entry.feeMinor());
        if (fee > 0) {
            Accounts feeAccounts = accounts("PROCESSING_FEE");
            if (entry.feeMinor() < 0) feeAccounts = new Accounts(feeAccounts.credit(), feeAccounts.debit());
            ledger.post(journal(entry, owner, "PROCESSING_FEE",
                    "BALANCE_FEE", entry.id() + ":fee", null, fee, feeAccounts));
            counts.fees += entry.feeMinor();
        }
    }

    private BalanceLedgerPort.Journal journal(ProviderBalancePort.Entry entry,
                                               BalanceLedgerPort.Owner owner,
                                               String journalType, String sourceType,
                                               String sourceId, String balanceId,
                                               long amount, Accounts accounts) {
        List<Map<String, Object>> lines = List.of(
                Map.of("account_code", accounts.debit(), "direction", "DEBIT",
                        "amount_minor", amount, "currency", entry.currency()),
                Map.of("account_code", accounts.credit(), "direction", "CREDIT",
                        "amount_minor", amount, "currency", entry.currency()));
        return new BalanceLedgerPort.Journal(ids.nextFinancialJournalId(), owner.paymentId(),
                owner.refundId(), owner.disputeId(), journalType, balanceId, sourceType,
                sourceId, amount, entry.currency(), entry.createdAt(), clock.now(), write(lines));
    }

    private static boolean requiresOwner(String type) {
        return switch (type) {
            case "charge", "payment", "refund", "payment_refund", "refund_failure", "adjustment" -> true;
            default -> false;
        };
    }

    private static String journalType(String type, long signedAmount,
                                      BalanceLedgerPort.Owner owner) {
        return switch (type) {
            case "charge", "payment" -> "CAPTURE";
            case "refund", "payment_refund" -> "REFUND";
            case "refund_failure" -> "REFUND_REVERSAL";
            case "adjustment" -> {
                if (owner.disputeId() == null)
                    throw new IllegalStateException("BALANCE_ADJUSTMENT_NOT_DISPUTE");
                yield signedAmount < 0 ? "DISPUTE_WITHDRAWAL" : "DISPUTE_REINSTATEMENT";
            }
            case "stripe_fee" -> "PROCESSING_FEE";
            case "payout" -> "PAYOUT_SETTLEMENT";
            default -> throw new IllegalStateException("BALANCE_TYPE_UNSUPPORTED:" + type);
        };
    }

    private static Accounts accounts(String type) {
        return switch (type) {
            case "CAPTURE" -> new Accounts("PROVIDER_RECEIVABLE", "CUSTOMER_PAYMENT_CLEARING");
            case "REFUND_REVERSAL" -> new Accounts("PROVIDER_RECEIVABLE", "CUSTOMER_REFUND_CLEARING");
            case "REFUND" -> new Accounts("CUSTOMER_REFUND_CLEARING", "PROVIDER_RECEIVABLE");
            case "DISPUTE_WITHDRAWAL" -> new Accounts("DISPUTE_HOLD_OR_LOSS", "PROVIDER_RECEIVABLE");
            case "DISPUTE_REINSTATEMENT" -> new Accounts("PROVIDER_RECEIVABLE", "DISPUTE_HOLD_OR_LOSS");
            case "PROCESSING_FEE" -> new Accounts("PROCESSING_FEE_EXPENSE", "PROVIDER_RECEIVABLE");
            case "PAYOUT_SETTLEMENT" -> new Accounts("BANK_SETTLEMENT_CLEARING", "PROVIDER_RECEIVABLE");
            default -> throw new IllegalStateException("unsupported journal type " + type);
        };
    }

    private static void addTotal(String type, long amount, MutableCounts counts) {
        switch (type) {
            case "CAPTURE" -> counts.captured += amount;
            case "REFUND" -> counts.refunded += amount;
            case "REFUND_REVERSAL" -> counts.refunded -= amount;
            case "DISPUTE_WITHDRAWAL" -> counts.disputed += amount;
            case "DISPUTE_REINSTATEMENT" -> counts.disputed += amount;
            case "PROCESSING_FEE" -> counts.fees += amount;
            case "PAYOUT_SETTLEMENT" -> counts.payouts += amount;
            default -> throw new IllegalStateException("unsupported total type " + type);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Accounts(String debit, String credit) {}

    private static final class MutableCounts {
        int scanned; long captured; long refunded; long disputed; long fees; long payouts;
        ReconciliationPort.Counts snapshot() {
            return new ReconciliationPort.Counts(scanned, 0, 0, 0,
                    captured, refunded, disputed, fees, payouts);
        }
    }
}
