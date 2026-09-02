package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.app.finance.data.db.entity.SettlementEntity
import kotlinx.coroutines.flow.Flow

/**
 * Settlements, and the balances they move — FR-SHR-04, FR-SHR-05.
 *
 * A settlement is money between you and a person that is not consumption: a
 * repayment, or a loan made outright. Nothing here touches a rollup, which is
 * the point — see [SettlementEntity].
 */
@Dao
interface SettlementDao {

    @Insert
    suspend fun insert(settlement: SettlementEntity): Long

    @Delete
    suspend fun delete(settlement: SettlementEntity)

    @Query("SELECT * FROM settlement WHERE id = :id")
    suspend fun byId(id: Long): SettlementEntity?

    @Query("SELECT * FROM settlement ORDER BY settled_on DESC, id DESC")
    suspend fun all(): List<SettlementEntity>

    @Query(
        "SELECT * FROM settlement WHERE person_id = :personId " +
            "ORDER BY settled_on DESC, id DESC",
    )
    fun observeForPerson(personId: Long): Flow<List<SettlementEntity>>

    /**
     * What every person's balance comes to — FR-SHR-05.
     *
     * Three signed quantities over one person:
     *
     *  1. their shares of expenses **you** paid — they owe you
     *  2. your share of expenses **they** paid — you owe them
     *  3. settlements already made, signed
     *
     * Positive means they owe you; negative means you owe them.
     *
     * `status = 0` on both expense terms because a pending row (FR-REC-02) is
     * excluded from every other aggregate in this app and a debt is no
     * different: a recurring rule that fired but was never confirmed has not
     * happened yet, and nobody owes anything for it.
     *
     * Correlated subqueries rather than three `LEFT JOIN`s: joining two
     * one-to-many tables against `person` multiplies their rows together and
     * silently inflates both sums. Each subquery is driven by its own index
     * (`ix_share_person`, `ix_expense_payer`, `ix_settlement_person`) over a
     * table that grows with shared expenses, not with the 20,000-row ledger.
     *
     * No rollup table backs this. It is a handful of people and an indexed sum;
     * §22 found rollups are where the subtle bugs live, so this one is earned
     * by measurement or not at all.
     */
    @Query(
        """
        SELECT p.id AS personId,
               p.name AS personName,
               p.is_archived AS archived,
               (EXISTS (SELECT 1 FROM expense_share WHERE person_id = p.id)
                 OR EXISTS (SELECT 1 FROM expense    WHERE payer_person_id = p.id)
                 OR EXISTS (SELECT 1 FROM settlement WHERE person_id = p.id)) AS hasHistory,
               IFNULL((SELECT SUM(s.share_minor)
                         FROM expense_share s
                         JOIN expense e ON e.id = s.expense_id
                        WHERE s.person_id = p.id AND e.status = 0), 0)
             - IFNULL((SELECT SUM(e.amount_minor)
                         FROM expense e
                        WHERE e.payer_person_id = p.id AND e.status = 0), 0)
             - IFNULL((SELECT SUM(t.amount_minor)
                         FROM settlement t
                        WHERE t.person_id = p.id), 0) AS balanceMinor
          FROM person p
         ORDER BY p.sort_order, p.name
        """,
    )
    fun observeBalances(): Flow<List<PersonBalanceRow>>

    @Query(
        """
        SELECT IFNULL((SELECT SUM(s.share_minor)
                         FROM expense_share s
                         JOIN expense e ON e.id = s.expense_id
                        WHERE s.person_id = :personId AND e.status = 0), 0)
             - IFNULL((SELECT SUM(e.amount_minor)
                         FROM expense e
                        WHERE e.payer_person_id = :personId AND e.status = 0), 0)
             - IFNULL((SELECT SUM(t.amount_minor)
                         FROM settlement t
                        WHERE t.person_id = :personId), 0)
        """,
    )
    suspend fun balanceOf(personId: Long): Long
}

/** One person's net position — FR-SHR-05. Positive: they owe you. */
data class PersonBalanceRow(
    val personId: Long,
    val personName: String,
    val archived: Boolean,
    /**
     * Whether any expense, share or settlement names them — FR-SHR-01.
     *
     * Carried on the balance row rather than fetched per person, because the
     * People screen shows the delete control for every row at once and
     * `PersonDao.hasHistory` one row at a time would be a query per name. It
     * is the same three `EXISTS` clauses; a balance of zero is *not* the same
     * question, since somebody who borrowed ৳500 and repaid it is square and
     * still has history.
     */
    val hasHistory: Boolean,
    val balanceMinor: Long,
)
