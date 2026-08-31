package com.app.finance.data.repo

import com.app.finance.core.text.NameKey
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.util.UUID

/**
 * The people you split expenses with — FR-SHR-01.
 *
 * Deliberately thin. A person is a name and an archive flag; everything
 * interesting about them lives in the expenses, shares and settlements that
 * reference them, and is read through [SettlementRepository].
 *
 * **Archive, don't delete**, exactly as with categories. Every foreign key to
 * `person` is `ON DELETE RESTRICT`, so deleting somebody who appears in a
 * single old dinner would mean rewriting that history. [delete] exists only for
 * the case the income sources set the precedent for (FR-IS-06): somebody added
 * by mistake, with nothing pointing at them yet, guarded by a count above and
 * the foreign key below.
 */
class PersonRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.personDao()

    fun observeAll(): Flow<List<PersonEntity>> = dao.observeAll()

    /** What the split picker binds to — FR-CAT-08's rule, applied to people. */
    fun observeActive(): Flow<List<PersonEntity>> = dao.observeActive()

    suspend fun byId(id: Long): PersonEntity? = dao.byId(id)

    suspend fun all(): List<PersonEntity> = dao.all()

    /**
     * Adds somebody, or hands back the person of that name who already exists.
     *
     * Idempotent on [NameKey] because the entry sheet lets you type a name
     * inline rather than making you visit a manager screen first — and typing
     * "rahim" when "Rahim" exists must reach the same balance, not open a
     * second one beside it. The unique index is the backstop; this is the path
     * that keeps the user from ever meeting it.
     */
    suspend fun findOrCreate(name: String): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val key = NameKey.of(name)
        dao.byNameKey(key)?.let { return SaveOutcome.Saved(it.id) }

        val now = clock.millis()
        return runCatchingWrite {
            dao.insert(
                PersonEntity(
                    uuid = UUID.randomUUID().toString(),
                    name = name.trim(),
                    nameKey = key,
                    sortOrder = dao.nextSortOrder(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toPersonError()) },
        )
    }

    suspend fun rename(id: Long, name: String): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val existing = dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND)
        return runCatchingWrite {
            dao.update(
                existing.copy(
                    name = name.trim(),
                    nameKey = NameKey.of(name),
                    updatedAt = clock.millis(),
                ),
            )
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toPersonError()) },
        )
    }

    /**
     * Hides somebody from the pickers without touching their history.
     *
     * Their balance stays readable and stays correct — an archived person you
     * still owe is precisely the person you must not lose sight of, so the
     * balances screen keeps showing them while they are not square.
     */
    suspend fun setArchived(id: Long, archived: Boolean): SaveOutcome {
        dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND)
        return runCatchingWrite {
            dao.setArchived(id, archived, clock.millis())
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toPersonError()) },
        )
    }

    /**
     * Removes somebody nothing references — FR-IS-06's shape, for people.
     *
     * The count above and the foreign key below say the same thing twice on
     * purpose: the check gives the user a sentence they can act on, the
     * constraint makes it true even if the check is ever wrong.
     */
    suspend fun delete(id: Long): SaveOutcome {
        dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND)
        if (dao.hasHistory(id)) return SaveOutcome.Rejected(EntryError.PERSON_HAS_HISTORY)
        return runCatchingWrite {
            dao.delete(id)
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toPersonError()) },
        )
    }

    /*
     * There is deliberately no `move`.
     *
     * `person.sort_order` exists and is maintained, because the column is worth
     * having and `nextSortOrder` keeps it dense. But the balances screen groups
     * by *direction* — they owe you, you owe them, square — and within a group
     * the order is a detail nobody is choosing between. A manual reorder there
     * would be a control whose effect the user cannot see, and writing one that
     * nothing surfaced would be a second implementation kept warm by nothing,
     * which is what §20 removed `pageAfter` for.
     *
     * Categories and income sources have `move` because their screens are
     * *lists the user arranges*. This one is a list the balances arrange.
     */

    /** See [toWriteError] — only the constraint half is this repository's. */
    private fun Throwable.toPersonError(): EntryError = toWriteError("save a person") {
        when {
            it.message?.contains("UNIQUE", ignoreCase = true) == true -> EntryError.DUPLICATE_NAME
            else -> null
        }
    }
}
