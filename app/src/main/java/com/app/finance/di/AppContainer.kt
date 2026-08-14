package com.app.finance.di

import android.content.Context
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.ExpenseRepository
import java.time.Clock

/**
 * Hand-rolled dependency container — 04-system-architecture.md §2.3.
 *
 * Hilt is the conventional answer and the wrong one here: an annotation
 * processor, generated component classes, and a graph constructed inside
 * `Application.onCreate`, all sitting on the cold-start path this app budgets
 * at 800 ms. For roughly a dozen injectable types a plain container is smaller,
 * faster, and fully comprehensible.
 *
 * **Every field must stay `by lazy`.** That is the property doing the work: the
 * container is constructed during `onCreate` but nothing inside it is — the
 * database opens on the first query, off the main thread, after the first frame
 * has already been drawn. A single eager field here would put `SQLiteDatabase`
 * back on the startup path and quietly cost the cold-start budget.
 *
 * The honest trade-off: this scales poorly past ~50 types. The MVP has ~12.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Injected rather than read statically, so tests can pin "today". */
    val clock: Clock = Clock.systemDefaultZone()

    val db: AppDatabase by lazy { AppDatabase.build(appContext) }

    val expenseRepo: ExpenseRepository by lazy { ExpenseRepository(db, clock) }
    val categoryRepo: CategoryRepository by lazy { CategoryRepository(db, clock) }
    val appMetaRepo: AppMetaRepository by lazy { AppMetaRepository(db, clock) }
}
