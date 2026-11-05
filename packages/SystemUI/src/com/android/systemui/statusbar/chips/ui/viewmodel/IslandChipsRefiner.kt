/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.chips.ui.viewmodel

import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Status bar activities that the dynamic island shows itself, so they get no stock chip. */
object IslandChipClaims {
    data class Claims(
        val notificationKeys: Set<String> = emptySet(),
        val screenRecord: Boolean = false,
    )

    private val _claims = MutableStateFlow(Claims())
    val claims: StateFlow<Claims> = _claims

    fun update(claims: Claims) {
        _claims.value = claims
    }
}

/** Drops the stock chips of activities the dynamic island already shows. */
class IslandChipsRefiner @Inject constructor() : OngoingActivityChipsRefiner {
    override fun transform(
        input: MultipleOngoingActivityChipsModel
    ): MultipleOngoingActivityChipsModel {
        val claims = IslandChipClaims.claims.value
        if (claims.notificationKeys.isEmpty() && !claims.screenRecord) return input
        val claimed = { key: String ->
            key in claims.notificationKeys ||
                (claims.screenRecord && key == ScreenRecordChipViewModel.KEY)
        }
        if ((input.active + input.overflow).none { claimed(it.key) }) return input
        val kept = (input.active + input.overflow).filterNot { claimed(it.key) }
        val activeCount = input.active.size
        return input.copy(active = kept.take(activeCount), overflow = kept.drop(activeCount))
    }
}
