package com.app.finance.domain.model

/**
 * A node in the two-level category tree.
 *
 * Pure Kotlin with no Android or Room types, so the budget-grouping logic that
 * consumes it is testable on the JVM in milliseconds — which is the point of
 * keeping `domain/` free of platform imports.
 *
 * Depth is capped at two by database trigger, so [children] of a child is
 * always empty; the type stays recursive only because a flat pair of classes
 * would duplicate every field.
 */
data class CategoryNode(
    val id: Long,
    val name: String,
    val nature: Nature,
    val isSystem: Boolean,
    val isArchived: Boolean,
    val children: List<CategoryNode>,
) {
    val isRoot: Boolean get() = children.isNotEmpty() || isSystem

    /** Only a childless non-root may carry an expense or a budget. */
    val isSelectableLeaf: Boolean get() = children.isEmpty() && !isSystem && !isArchived

    val activeChildren: List<CategoryNode> get() = children.filter { !it.isArchived }
}
