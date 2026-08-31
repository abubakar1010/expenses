package com.app.finance.data.repo

import com.app.finance.core.money.Money
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.PersonBalanceRow
import com.app.finance.data.db.entity.SettlementEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Money between you and a person that is not consumption — FR-SHR-04, and the
 * balances it moves — FR-SHR-05.
 *
 * A repayment, or a loan made outright. **Nothing here touches a rollup**, and
 * that is the point rather than an omission: a friend settling up is your own
 * money coming home, and counting it as income would lift the savings rate
 * every time somebody paid you back.
 *
 * There is no rollup table behind the balances either. Share rows scale with
 * how often you split rather than with the twenty thousand rows of the ledger,
 * and people number in the tens, so the balance is an indexed sum over a small
 * table. §22 found rollups are where the subtle bugs live; this one is earned
 * by measurement or not at all.
 */
class SettlementRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.settlementDao()

    /** Every person's net position — positive means they owe you. */
    fun observeBalances(): Flow<List<PersonBalanceRow>> = dao.observeBalances()

    fun observeForPerson(personId: Long): Flow<List<SettlementEntity>> =
        dao.observeForPerson(personId)

    suspend fun balanceOf(personId: Long): Money = Money(dao.balanceOf(personId))

    /**
     * Records money moving — FR-SHR-04.
     *
     * [amount] is signed and the sign is the whole meaning: **positive means
     * they paid you**, negative means you paid them. One signed column rather
     * than a direction flag, so the balance is a single `SUM` that cannot
     * disagree with itself.
     *
     * A settlement dated in the future is refused for the same reason an
     * expense is (FR-EXP-02): it has not happened, and a balance that counts it
     * is a balance that is wrong until it does.
     */
    suspend fun record(
        personId: Long,
        amount: Money,
        settledOn: LocalDate = LocalDate.now(clock),
        method: PaymentMethod = PaymentMethod.DEFAULT,
        note: String? = null,
    ): SaveOutcome {
        if (amount.isZero) return SaveOutcome.Rejected(EntryError.ZERO_AMOUNT)
        if (settledOn.isAfter(LocalDate.now(clock))) {
            return SaveOutcome.Rejected(EntryError.FUTURE_DATE)
        }
        db.personDao().byId(personId)
            ?: return SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND)

        val now = clock.millis()
        return runCatchingWrite {
            dao.insert(
                SettlementEntity(
                    uuid = UUID.randomUUID().toString(),
                    personId = personId,
                    amountMinor = amount.paisa,
                    settledOn = settledOn.toEpochDay(),
                    paymentMethod = method.code,
                    note = note?.trim()?.ifBlank { null },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toSettlementError()) },
        )
    }

    /**
     * Removes a settlement and returns it, so the caller can offer Undo.
     *
     * NFR-USE-03: every destructive action is undoable, and after this the row
     * exists nowhere else.
     */
    suspend fun delete(id: Long): SettlementEntity? {
        val row = dao.byId(id) ?: return null
        dao.delete(row)
        return row
    }

    /** Re-inserts a deleted settlement verbatim, UUID included, for Undo. */
    suspend fun restore(row: SettlementEntity): Long = dao.insert(row.copy(id = 0))

    private fun Throwable.toSettlementError(): EntryError =
        toWriteError("record a settlement") {
            when {
                it.message?.contains("amount_minor") == true -> EntryError.ZERO_AMOUNT
                else -> null
            }
        }
}
